// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as kinesis from 'aws-cdk-lib/aws-kinesis';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { CloudFrontFargateService } from './constructs/cloudFrontFargateService';

export interface CdcStackProps extends cdk.StackProps {
  readonly webAclArn: string;
  readonly outboxEcsCluster: ecs.ICluster;
  readonly flightQueue: sqs.IQueue;
}

/** CDC implementation: DynamoDB -> Kinesis Data Streams -> relay to SQS. */
export class CdcStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: CdcStackProps) {
    super(scope, id, props);

    // Unrelayed changes survive an SQS outage only while they are retained in the stream: 7 days is
    // the recovery window, and the alarm below fires long before records get close to expiring.
    const cdcStream = new kinesis.Stream(this, 'flightsCDCStream', {
      encryption: kinesis.StreamEncryption.MANAGED,
      retentionPeriod: cdk.Duration.days(7),
    });
    new cloudwatch.Alarm(this, 'RelayLagAlarm', {
      alarmDescription: 'CDC relay is falling behind: change records are not being relayed to SQS',
      metric: cdcStream.metricGetRecordsIteratorAgeMilliseconds({
        statistic: cloudwatch.Stats.MAXIMUM,
        period: cdk.Duration.minutes(5),
      }),
      threshold: cdk.Duration.hours(1).toMilliseconds(),
      evaluationPeriods: 3,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    // The relay polls continuously, so no successful GetRecords call means it has stopped consuming
    // (SUM counts successes only; failed calls report 0).
    new cloudwatch.Alarm(this, 'RelayStoppedAlarm', {
      alarmDescription: 'CDC relay is not successfully reading the change stream',
      metric: cdcStream.metricGetRecordsSuccess({
        statistic: cloudwatch.Stats.SUM,
        period: cdk.Duration.minutes(5),
      }),
      threshold: 1,
      evaluationPeriods: 3,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    });

    const tableDefaults = {
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
      pointInTimeRecoverySpecification: { pointInTimeRecoveryEnabled: true },
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    } satisfies Partial<dynamodb.TableProps>;

    // The table name "flight" is what Spring Cloud AWS derives from the Flight entity.
    const flightTable = new dynamodb.Table(this, 'flight', {
      ...tableDefaults,
      tableName: 'flight',
      kinesisStream: cdcStream,
      partitionKey: { name: 'id', type: dynamodb.AttributeType.STRING },
    });

    // State tables for the Kinesis binder (checkpoints and shard locks). Pre-created so the
    // application never needs dynamodb:CreateTable. Key schemas match Spring Cloud AWS's
    // DynamoDbMetadataStore and DynamoDbLockRepository.
    const checkpointTable = new dynamodb.Table(this, 'BinderCheckpoints', {
      ...tableDefaults,
      partitionKey: { name: 'metadataKey', type: dynamodb.AttributeType.STRING },
      timeToLiveAttribute: 'expireAt',
    });
    const lockTable = new dynamodb.Table(this, 'BinderLocks', {
      ...tableDefaults,
      partitionKey: { name: 'lockKey', type: dynamodb.AttributeType.STRING },
      timeToLiveAttribute: 'expireAt',
    });

    const taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'CDC sample task role (DynamoDB, Kinesis read, SQS)',
    });
    flightTable.grantReadWriteData(taskRole);
    checkpointTable.grantReadWriteData(taskRole);
    lockTable.grantReadWriteData(taskRole);
    cdcStream.grantRead(taskRole);
    props.flightQueue.grantSendMessages(taskRole);
    props.flightQueue.grantConsumeMessages(taskRole);

    new CloudFrontFargateService(this, 'CdcApp', {
      cluster: props.outboxEcsCluster,
      imageDirectory: '../cdc-implementation',
      taskRole,
      webAclArn: props.webAclArn,
      environment: {
        SQS_QUEUE_NAME: props.flightQueue.queueName,
        KINESIS_STREAM_NAME: cdcStream.streamName,
        BINDER_CHECKPOINT_TABLE: checkpointTable.tableName,
        BINDER_LOCK_TABLE: lockTable.tableName,
        AWS_REGION: this.region,
      },
    });
  }
}
