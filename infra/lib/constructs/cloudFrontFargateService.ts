// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { Validations } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecsPatterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as cr from 'aws-cdk-lib/custom-resources';

export interface CloudFrontFargateServiceProps {
  readonly cluster: ecs.ICluster;
  /** Directory containing the application's Dockerfile. */
  readonly imageDirectory: string;
  readonly taskRole: iam.IRole;
  readonly environment?: Record<string, string>;
  readonly secrets?: Record<string, ecs.Secret>;
  /** ARN of a CLOUDFRONT-scoped WAF WebACL (must live in us-east-1). */
  readonly webAclArn: string;
}

const CONTAINER_PORT = 8080;
/** Construct ID of the singleton Lambda backing every AwsCustomResource in a stack. */
const AWS_CUSTOM_RESOURCE_SINGLETON_ID = 'AWS679f53fac002430cb0da5b7982bd2287';

/**
 * Spring Boot service on Fargate behind an internal ALB, published over HTTPS through
 * CloudFront using a VPC origin. The ALB has no public address and its security group
 * only accepts traffic from CloudFront's origin-facing prefix list.
 */
export class CloudFrontFargateService extends Construct {
  public readonly service: ecsPatterns.ApplicationLoadBalancedFargateService;
  public readonly distribution: cloudfront.Distribution;

  constructor(scope: Construct, id: string, props: CloudFrontFargateServiceProps) {
    super(scope, id);

    const logBucket = new s3.Bucket(this, 'AccessLogs', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      // CloudFront standard logging writes objects with ACLs.
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_PREFERRED,
      enforceSSL: true,
      lifecycleRules: [{ expiration: cdk.Duration.days(90) }],
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });

    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDef', {
      cpu: 512,
      memoryLimitMiB: 1024,
      taskRole: props.taskRole,
      runtimePlatform: {
        operatingSystemFamily: ecs.OperatingSystemFamily.LINUX,
        cpuArchitecture: ecs.CpuArchitecture.ARM64,
      },
    });
    taskDefinition.addVolume({ name: 'tmp' });

    const container = taskDefinition.addContainer('app', {
      image: ecs.ContainerImage.fromAsset(props.imageDirectory, {
        platform: ecrAssets.Platform.LINUX_ARM64,
      }),
      portMappings: [{ containerPort: CONTAINER_PORT }],
      environment: {
        // Exposes Swagger UI for the README walkthrough (still behind the WAF IP allow list).
        SPRING_PROFILES_ACTIVE: 'demo',
        ...props.environment,
      },
      secrets: props.secrets,
      readonlyRootFilesystem: true,
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'app',
        logGroup: new logs.LogGroup(this, 'AppLogs', {
          retention: logs.RetentionDays.ONE_MONTH,
          removalPolicy: cdk.RemovalPolicy.DESTROY,
        }),
      }),
      // Liveness only: a database/queue outage must not make ECS kill the task.
      healthCheck: {
        command: ['CMD-SHELL', `curl -fsS http://localhost:${CONTAINER_PORT}/actuator/health/liveness || exit 1`],
        interval: cdk.Duration.seconds(15),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(90),
      },
    });
    container.addMountPoints({ containerPath: '/tmp', sourceVolume: 'tmp', readOnly: false });

    this.service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, 'Service', {
      cluster: props.cluster,
      taskDefinition,
      desiredCount: 2,
      publicLoadBalancer: false,
      openListener: false,
      circuitBreaker: { rollback: true },
      minHealthyPercent: 100,
      healthCheckGracePeriod: cdk.Duration.seconds(120),
    });

    // Readiness drives load balancer routing.
    this.service.targetGroup.configureHealthCheck({
      path: '/actuator/health/readiness',
      interval: cdk.Duration.seconds(15),
      timeout: cdk.Duration.seconds(5),
      healthyThresholdCount: 2,
      unhealthyThresholdCount: 3,
      healthyHttpCodes: '200',
    });
    this.service.loadBalancer.logAccessLogs(logBucket, 'alb');

    const autoScaling = this.service.service.autoScaleTaskCount({ minCapacity: 2, maxCapacity: 6 });
    autoScaling.scaleOnCpuUtilization('CpuScaling', {
      targetUtilizationPercent: 45,
      scaleInCooldown: cdk.Duration.seconds(60),
      scaleOutCooldown: cdk.Duration.seconds(30),
    });

    // Only CloudFront (via the VPC origin) may reach the ALB. The managed prefix list ID
    // differs per region, so resolve it at deploy time instead of hard-coding it.
    const prefixListLookup = new cr.AwsCustomResource(this, 'CloudFrontPrefixList', {
      onUpdate: {
        service: 'EC2',
        action: 'describeManagedPrefixLists',
        parameters: {
          Filters: [{ Name: 'prefix-list-name', Values: ['com.amazonaws.global.cloudfront.origin-facing'] }],
        },
        physicalResourceId: cr.PhysicalResourceId.of('cloudfront-origin-facing-prefix-list'),
        outputPaths: ['PrefixLists.0.PrefixListId'],
      },
      // Read-only, deploy-time only. ec2:Describe* actions do not support resource-level permissions,
      // so Resource "*" is required: https://docs.aws.amazon.com/IAM/latest/UserGuide/troubleshoot_policies.html
      policy: cr.AwsCustomResourcePolicy.fromSdkCalls({ resources: cr.AwsCustomResourcePolicy.ANY_RESOURCE }),
      installLatestAwsSdk: false,
    });
    this.service.loadBalancer.connections.allowFrom(
      ec2.Peer.prefixList(prefixListLookup.getResponseField('PrefixLists.0.PrefixListId')),
      ec2.Port.tcp(80),
      'CloudFront VPC origin',
    );

    this.distribution = new cloudfront.Distribution(this, 'Distribution', {
      comment: `${cdk.Stack.of(this).stackName} API`,
      defaultBehavior: {
        origin: origins.VpcOrigin.withApplicationLoadBalancer(this.service.loadBalancer, {
          protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
          httpPort: 80,
        }),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER,
        responseHeadersPolicy: cloudfront.ResponseHeadersPolicy.SECURITY_HEADERS,
      },
      webAclId: props.webAclArn,
      httpVersion: cloudfront.HttpVersion.HTTP2_AND_3,
      // IPv4 only: the WAF allow list is IPv4 (what https://checkip.amazonaws.com reports). With IPv6
      // enabled, dual-stack clients connect over IPv6 and are blocked by the default action.
      enableIpv6: false,
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      enableLogging: true,
      logBucket,
      logFilePrefix: 'cloudfront',
    });

    new cdk.CfnOutput(this, 'ApiUrl', { value: `https://${this.distribution.distributionDomainName}` });

    const ack = (id: string, reason: string) => ({ id: `AwsSolutions::AwsSolutions-${id}`, reason });
    Validations.of(this).acknowledge(
      ack('S1', 'This bucket is the access-log destination itself; logging it to another bucket adds no value for the sample.'),
      ack('ECS2', 'Environment variables only carry non-secret configuration (names, endpoints, region); secrets use ECS secrets.'),
      ack('CFR1', 'No geo restriction requirement; access is limited by the WAF IP allow list.'),
      ack('CFR4', 'No custom domain: the default *.cloudfront.net certificate does not allow setting a minimum TLS version.'),
      ack('IAM5[Resource::*]', 'ecr:GetAuthorizationToken and ec2:DescribeManagedPrefixLists do not support resource-level permissions.'),
    );
    // AwsCustomResource uses a stack-level singleton Lambda function.
    const crProvider = cdk.Stack.of(this).node.tryFindChild(AWS_CUSTOM_RESOURCE_SINGLETON_ID);
    if (crProvider) {
      Validations.of(crProvider).acknowledge(
        ack(
          'IAM4[Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole]',
          'CDK-managed provider for AwsCustomResource; the basic execution role only grants CloudWatch Logs access.',
        ),
      );
    }
  }
}
