import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as ecs_patterns from "aws-cdk-lib/aws-ecs-patterns";
import * as kinesis from "aws-cdk-lib/aws-kinesis";
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as iam from 'aws-cdk-lib/aws-iam';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';

interface ICdcStack extends cdk.StackProps {
    webACL: wafv2.CfnWebACL;
    outboxEcsCluster: ecs.Cluster;
    flightQueue: sqs.Queue;
  }

export class CdcStack extends cdk.Stack {
    constructor(scope: Construct, id: string, props: ICdcStack) {

        super(scope, id, props);

        const appRole = new iam.Role(this, 'appRole', {
            assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'Role for ECS Tasks to interact with the relevant AWS services',
        });
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
        appRole.attachInlinePolicy(sqsPolicy);

        //Kinesis Stream to capture change from dynamoDB table
        const cdcStream = new kinesis.Stream(this, 'flightsCDCStream', {
            streamName: 'flightsCDCStream'
        })
        const kinesisPolicy = new iam.Policy(this, 'kinesisPolicy', {
            statements: [new iam.PolicyStatement({
                actions: [
                    'kinesis:*',
                ],
                resources: [cdcStream.streamArn],
            })],
        })
        appRole.attachInlinePolicy(kinesisPolicy);

        //Our DynamoDB table
        const flightTable = new dynamodb.Table(this, 'flight', {
            tableName: 'flight',
            kinesisStream: cdcStream,
            partitionKey: {
                name: 'id',
                type: dynamodb.AttributeType.STRING,
            },
            deletionProtection: false
        });
        const dynamoDBPolicy = new iam.Policy(this, 'dynamoDBPolicy', {
            statements: [new iam.PolicyStatement({
                actions: [
                    'dynamodb:*',
                ],
                resources: ['*'],
            })],
        })
        appRole.attachInlinePolicy(dynamoDBPolicy);


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
                image: ecs.ContainerImage.fromAsset('../cdc-implementation'),// {platform: aws_ecr_assets.Platform.LINUX_AMD64}),
                containerPort: 8080,
                environment: {
                    'sqsqueuename': props.flightQueue.queueName,
                    'kinesisstreamname': cdcStream.streamName,
                    'AWS_REGION': this.region
                },
                taskRole: appRole
            }
        });
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