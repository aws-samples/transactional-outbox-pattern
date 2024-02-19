import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as logs from "aws-cdk-lib/aws-logs"
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as iam from 'aws-cdk-lib/aws-iam';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';

export class BaseStack extends cdk.Stack {

  public readonly webACL: wafv2.CfnWebACL;
  public readonly outboxEcsCluster: ecs.Cluster;
  public readonly vpc: ec2.Vpc;
  public readonly flightQueue: sqs.Queue;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {

    super(scope, id, props);

    // Parameters
    const myPublicIP = new cdk.CfnParameter(this, "myPublicIP", {
      type: "String",
      description: "The public IP used to access the API."});

    // Our VPC
    const cwLogs = new logs.LogGroup(this, 'Log', {
      logGroupName: '/aws/vpc/flowlogs',
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });
    this.vpc = new ec2.Vpc(this, "outbox-vpc", {
      maxAzs: 2,
      natGateways: 1,
      flowLogs: {
        's3': {
          destination: ec2.FlowLogDestination.toCloudWatchLogs(cwLogs),
          trafficType: ec2.FlowLogTrafficType.ALL,
        }
      }
    });

    // WebACL and WAF definition (to protect our ALB)
    const allowListIPSet = new wafv2.CfnIPSet(this, "AllowListIPSet", {
      name: "AllowListIPSet",
      addresses: [myPublicIP.valueAsString],
      ipAddressVersion: "IPV4",
      scope: "REGIONAL",
    });
    const allowListIPSetRuleProperty: wafv2.CfnWebACL.RuleProperty = {
      priority: 0,
      name: "AllowListIPSet-Rule",
      action: {
        allow: {},
      },
      statement: {
        ipSetReferenceStatement: {
          arn: allowListIPSet.attrArn,
        },
      },
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        metricName: "AllowListIPSet-Rule",
        sampledRequestsEnabled: true,
      },
    };
    this.webACL = new wafv2.CfnWebACL(this, "WebAcl", {
      name: "WebAcl",
      defaultAction: { block: {} },
      scope: "REGIONAL",
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        metricName: "WebAcl",
        sampledRequestsEnabled: true,
      },
      rules: [allowListIPSetRuleProperty],
    });

    // Our Queue
    const flightDLQ = new sqs.Queue(this, 'FlightDLQ', {
      queueName: 'flightDLQ.fifo'
    });
    this.flightQueue = new sqs.Queue(this, 'FlightQueue',
      {
        queueName: 'flightQueue.fifo',
        deadLetterQueue: {
          maxReceiveCount: 10,
          queue: flightDLQ
        }
      }
    );

    //Our ECS Fargate Cluster in this VPC
    this.outboxEcsCluster = new ecs.Cluster(this, "outbox-ecs", {
      vpc: this.vpc,
      clusterName: "outboxEcsCluster",
      containerInsights: true,
    })
    
  }
}