import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as ecs_patterns from "aws-cdk-lib/aws-ecs-patterns";
import * as rds from 'aws-cdk-lib/aws-rds';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as iam from 'aws-cdk-lib/aws-iam';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';

interface IAuroraStack extends cdk.StackProps {
  webACL: wafv2.CfnWebACL;
  outboxEcsCluster: ecs.Cluster;
  vpc: ec2.Vpc;
  flightQueue: sqs.Queue;
}

export class AuroraStack extends cdk.Stack {

  constructor(scope: Construct, id: string, props: IAuroraStack) {
    super(scope, id, props);

    const sqsPolicy = new iam.Policy(this, 'SQSpolicy', {
      statements: [new iam.PolicyStatement({
        actions: [
          'sqs:SendMessage',
          'sqs:SendMessageBatch',
          'sqs:ReceiveMessage',
          'sqs:DeleteMessage',
          'sqs:DeleteMessageBatch',
          'sqs:GetQueueUrl',
          'sqs:ChangeMessageVisibility',
          'sqs:GetQueueAttributes',
          'sqs:ListQueues',
        ],
        resources: [props.flightQueue.queueArn],
      })],
    })
    const sqsRole = new iam.Role(this, 'sqsFullRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'Role for ECS Tasks to interact with SQS',
    });
    sqsRole.attachInlinePolicy(sqsPolicy);

    //Our Database
    const pgPassword = new secretsmanager.Secret(this, 'DBSecret', {
      secretName: "outboxDB-DBPassword",
      generateSecretString: {
        excludePunctuation: true
      }
    });
    const dbSecurityGroup = new ec2.SecurityGroup(this, 'dbsg', {
      vpc: props.vpc,
      description: "outbox database security group"
    })
    const appSecurityGroup = new ec2.SecurityGroup(this, 'appsg', {
      vpc: props.vpc,
      description: "outbox app security group"
    })
    const auroraServerlessRds = new rds.CfnDBCluster(this, "aurora-serverless", {
      engine: "aurora-postgresql",
      engineMode: "serverless",
      engineVersion: "13.11",
      databaseName: 'outboxPattern',
      dbClusterIdentifier: "outbox-pattern-dbcluster",
      masterUsername: 'dbaadmin',
      masterUserPassword: pgPassword.secretValue.unsafeUnwrap(),

      dbSubnetGroupName: new rds.CfnDBSubnetGroup(this, "db-subnet-group", {
        dbSubnetGroupDescription: `outbox-pattern-dbcluster subnet group`,
        subnetIds: props.vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }).subnetIds
      }).ref,
      vpcSecurityGroupIds: [dbSecurityGroup.securityGroupId],

      storageEncrypted: true,
      deletionProtection: false,
      backupRetentionPeriod: 14,
      enableHttpEndpoint: true,

      scalingConfiguration: {
        autoPause: true,
        secondsUntilAutoPause: 900,
        minCapacity: 2,
        maxCapacity: 4
      },
    })
    auroraServerlessRds.applyRemovalPolicy(cdk.RemovalPolicy.SNAPSHOT);

    //Our application in AWS Fargate + ALB
    const outboxApp = new ecs_patterns.ApplicationLoadBalancedFargateService(this, 'outbox svc', {
      cluster: props.outboxEcsCluster,
      desiredCount: 2,
      cpu: 256,
      memoryLimitMiB: 512,
      runtimePlatform: {
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
        cpuArchitecture: ecs.CpuArchitecture.ARM64,
      },
      taskImageOptions: {
        image: ecs.ContainerImage.fromAsset('../outboxApp'),// {platform: aws_ecr_assets.Platform.LINUX_AMD64}),
        containerPort: 8080,
        environment: {
          'springdatasourceurl': `jdbc:postgresql://` + auroraServerlessRds.attrEndpointAddress + `:5432/outboxPattern`,
          'springdatasourceusername': 'dbaadmin',
          'sqsqueuename': props.flightQueue.queueName,
          'AWS_REGION': this.region
        },
        secrets: {
          'pgpassword': ecs.Secret.fromSecretsManager(pgPassword)
        },
        taskRole: sqsRole
      },
      securityGroups: [appSecurityGroup]
    });
    dbSecurityGroup.addIngressRule(appSecurityGroup, ec2.Port.tcp(5432));
    
    //customize healthcheck on ALB
    outboxApp.targetGroup.configureHealthCheck({
      "port": 'traffic-port',
      "path": '/',
      "interval": cdk.Duration.seconds(5),
      "timeout": cdk.Duration.seconds(4),
      "healthyThresholdCount": 2,
      "unhealthyThresholdCount": 2,
      "healthyHttpCodes": "200,301,302"
    })
    
    //autoscaling - cpu
    const outboxAppAutoScaling = outboxApp.service.autoScaleTaskCount({
      maxCapacity: 6,
      minCapacity: 2
    })
    outboxAppAutoScaling.scaleOnCpuUtilization('CpuScaling', {
      targetUtilizationPercent: 45,
      policyName: "cpu autoscaling",
      scaleInCooldown: cdk.Duration.seconds(30),
      scaleOutCooldown: cdk.Duration.seconds(30)
    })
    const cfnWebACLAssociation = new wafv2.CfnWebACLAssociation(this, 'ALBWebACLAssociation', {
      resourceArn: outboxApp.loadBalancer.loadBalancerArn,
      webAclArn: props.webACL.attrArn,
    });
  }
}