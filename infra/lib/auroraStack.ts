// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { Validations } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { CloudFrontFargateService } from './constructs/cloudFrontFargateService';

export interface AuroraStackProps extends cdk.StackProps {
  readonly webAclArn: string;
  readonly outboxEcsCluster: ecs.ICluster;
  readonly vpc: ec2.IVpc;
  readonly flightQueue: sqs.IQueue;
}

const DATABASE_NAME = 'outboxPattern';

/** Transactional outbox implementation: Aurora PostgreSQL Serverless v2 + polling relay to SQS. */
export class AuroraStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: AuroraStackProps) {
    super(scope, id, props);

    const engine = rds.DatabaseClusterEngine.auroraPostgres({
      version: rds.AuroraPostgresEngineVersion.VER_17_9,
    });
    const parameterGroup = new rds.ParameterGroup(this, 'ClusterParams', {
      engine,
      parameters: { 'rds.force_ssl': '1' },
    });

    const cluster = new rds.DatabaseCluster(this, 'OutboxDb', {
      engine,
      parameterGroup,
      writer: rds.ClusterInstance.serverlessV2('writer'),
      // The relay polls every few seconds, so scale-to-zero would never pause.
      serverlessV2MinCapacity: 0.5,
      serverlessV2MaxCapacity: 4,
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      defaultDatabaseName: DATABASE_NAME,
      credentials: rds.Credentials.fromGeneratedSecret('dbaadmin'),
      storageEncrypted: true,
      backup: { retention: cdk.Duration.days(14) },
      cloudwatchLogsExports: ['postgresql'],
      // RDS Data API backs the console Query Editor used in the README walkthrough (IAM + secret auth).
      enableDataApi: true,
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
    });
    const dbSecret = cluster.secret!;

    const taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'Outbox sample task role (SQS send/consume)',
    });
    props.flightQueue.grantSendMessages(taskRole);
    props.flightQueue.grantConsumeMessages(taskRole);

    const app = new CloudFrontFargateService(this, 'OutboxApp', {
      cluster: props.outboxEcsCluster,
      imageDirectory: '../outbox-implementation',
      taskRole,
      webAclArn: props.webAclArn,
      environment: {
        SPRING_DATASOURCE_URL:
          `jdbc:postgresql://${cluster.clusterEndpoint.hostname}:${cluster.clusterEndpoint.port}/${DATABASE_NAME}` +
          '?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem',
        SQS_QUEUE_NAME: props.flightQueue.queueName,
        AWS_REGION: this.region,
      },
      secrets: {
        SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(dbSecret, 'username'),
        SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, 'password'),
      },
    });
    cluster.connections.allowDefaultPortFrom(app.service.service, 'Outbox app');
    // The app needs the database at startup (schema update): only create the service once the
    // writer instance is available, otherwise early tasks crash and trip the circuit breaker.
    // (Depend on the low-level resources only: a construct-level dependency would also cover the
    // security group rules and create a cycle.)
    const ecsService = app.service.service.node.defaultChild as cdk.CfnResource;
    for (const instance of cluster.node.findAll().filter((c): c is rds.CfnDBInstance => c instanceof rds.CfnDBInstance)) {
      ecsService.node.addDependency(instance);
    }

    const ack = (id: string, reason: string) => ({ id: `AwsSolutions::AwsSolutions-${id}`, reason });
    Validations.of(cluster).acknowledge(
      ack('RDS6', 'Password auth via Secrets Manager keeps the sample simple; IAM DB auth is not required.'),
      ack('RDS10', 'Sample stack: users are expected to destroy and redeploy. A final snapshot is kept on delete.'),
      ack('SMG4', 'ECS injects secrets only at task start; rotation without a coordinated task refresh would break the app.'),
    );
  }
}
