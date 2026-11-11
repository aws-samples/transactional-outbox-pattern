// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as sqs from 'aws-cdk-lib/aws-sqs';

/**
 * Shared networking and compute cluster, plus one downstream "payment" queue per implementation.
 * Each implementation gets its own queue so that its events are consumed by its own payment
 * service (a shared queue would let either app pick up the other's events).
 */
export class BaseStack extends cdk.Stack {
  public readonly outboxEcsCluster: ecs.Cluster;
  public readonly vpc: ec2.Vpc;
  /** Events relayed by the outbox implementation (AuroraStack). */
  public readonly outboxFlightQueue: sqs.Queue;
  /** Events relayed by the CDC implementation (CdcStack). */
  public readonly cdcFlightQueue: sqs.Queue;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const flowLogs = new logs.LogGroup(this, 'VpcFlowLogs', {
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });
    this.vpc = new ec2.Vpc(this, 'outbox-vpc', {
      maxAzs: 2,
      natGateways: 1,
      flowLogs: {
        cloudwatch: {
          destination: ec2.FlowLogDestination.toCloudWatchLogs(flowLogs),
          trafficType: ec2.FlowLogTrafficType.ALL,
        },
      },
    });

    // Construct IDs of the outbox queue pair are unchanged from earlier versions to avoid replacing it.
    this.outboxFlightQueue = this.createFifoQueueWithDlq('FlightQueue', 'FlightDLQ');
    this.cdcFlightQueue = this.createFifoQueueWithDlq('CdcFlightQueue', 'CdcFlightDLQ');

    this.outboxEcsCluster = new ecs.Cluster(this, 'outbox-ecs', {
      vpc: this.vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
    });
  }

  private createFifoQueueWithDlq(queueId: string, dlqId: string): sqs.Queue {
    const dlq = new sqs.Queue(this, dlqId, {
      fifo: true,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      retentionPeriod: cdk.Duration.days(14),
    });
    return new sqs.Queue(this, queueId, {
      fifo: true,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      deadLetterQueue: { maxReceiveCount: 10, queue: dlq },
    });
  }
}
