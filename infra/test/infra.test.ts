// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { AwsSolutionsChecks } from 'cdk-nag';
import { buildApp, OutboxAppStacks } from '../lib/app';
import { EdgeSecurityStack } from '../lib/edgeSecurityStack';

const ACCOUNT = '123456789012';
const REGION = 'eu-west-1';

function synthApp(props: cdk.AppProps = {}): { app: cdk.App; stacks: OutboxAppStacks } {
  const app = new cdk.App({
    ...props,
    context: {
      [`availability-zones:account=${ACCOUNT}:region=${REGION}`]: [`${REGION}a`, `${REGION}b`, `${REGION}c`],
      ...props.context,
    },
  });
  const stacks = buildApp(app, { account: ACCOUNT, region: REGION, allowedCidr: '203.0.113.10/32' });
  return { app, stacks };
}

const { stacks } = synthApp();
const templates = {
  edge: Template.fromStack(stacks.edge),
  base: Template.fromStack(stacks.base),
  aurora: Template.fromStack(stacks.aurora),
  cdc: Template.fromStack(stacks.cdc),
};

type Statement = { Action: string | string[]; Resource?: unknown; Effect: string };

function policyStatements(template: Template): Statement[] {
  return Object.values(template.findResources('AWS::IAM::Policy')).flatMap(
    (p) => (p as { Properties: { PolicyDocument: { Statement: Statement[] } } }).Properties.PolicyDocument.Statement,
  );
}

const asArray = <T>(v: T | T[]): T[] => (Array.isArray(v) ? v : [v]);

// Actions AWS does not allow scoping to a resource.
const UNSCOPABLE_ACTIONS = new Set(['ecr:GetAuthorizationToken', 'ec2:DescribeManagedPrefixLists']);

describe.each(Object.entries(templates))('%s stack IAM', (_name, template) => {
  test('no service-wide wildcard actions', () => {
    for (const statement of policyStatements(template)) {
      for (const action of asArray(statement.Action)) {
        expect(action).not.toMatch(/(^\*$|:\*$)/);
      }
    }
  });

  test('Resource "*" only for actions that cannot be scoped', () => {
    for (const statement of policyStatements(template)) {
      if (asArray(statement.Resource).includes('*')) {
        for (const action of asArray(statement.Action)) {
          expect(UNSCOPABLE_ACTIONS).toContain(action);
        }
      }
    }
  });
});

test('cdk-nag AwsSolutions reports no unacknowledged findings (errors or warnings)', () => {
  const outdir = fs.mkdtempSync(path.join(os.tmpdir(), 'cdk-nag-'));
  const { app } = synthApp({ outdir, context: { '@aws-cdk/core:validationReportJson': true } });
  cdk.Validations.of(app).addPlugins(new AwsSolutionsChecks(app));
  app.synth();

  const report = JSON.parse(fs.readFileSync(path.join(outdir, 'validation-report.json'), 'utf8')) as {
    pluginReports: { violations: { ruleName: string; severity: string }[] }[];
  };
  const violations = report.pluginReports.flatMap((r) => r.violations).map((v) => `${v.severity} ${v.ruleName}`);
  expect(violations).toEqual([]);
});

describe('BaseStack', () => {
  test('all queues are FIFO, encrypted and deny non-TLS access', () => {
    templates.base.resourceCountIs('AWS::SQS::Queue', 4);
    templates.base.allResourcesProperties('AWS::SQS::Queue', { FifoQueue: true, SqsManagedSseEnabled: true });
    templates.base.resourceCountIs('AWS::SQS::QueuePolicy', 4);
    templates.base.allResourcesProperties('AWS::SQS::QueuePolicy', {
      PolicyDocument: {
        Statement: Match.arrayWith([
          Match.objectLike({ Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } } }),
        ]),
      },
    });
  });

  test('each implementation publishes to and consumes from its own queue', () => {
    expect(stacks.base.outboxFlightQueue).not.toBe(stacks.base.cdcFlightQueue);
    // The outbox queue keeps its original logical ID so existing deployments do not replace it.
    expect(Object.keys(templates.base.findResources('AWS::SQS::Queue'))).toEqual(
      expect.arrayContaining([expect.stringMatching(/^FlightQueue/), expect.stringMatching(/^CdcFlightQueue/)]),
    );
    const queueEnv = (template: Template) => {
      const [taskDef] = Object.values(template.findResources('AWS::ECS::TaskDefinition')) as {
        Properties: { ContainerDefinitions: { Environment: { Name: string; Value: unknown }[] }[] };
      }[];
      return JSON.stringify(
        taskDef.Properties.ContainerDefinitions[0].Environment.find((e) => e.Name === 'SQS_QUEUE_NAME')!.Value,
      );
    };
    expect(queueEnv(templates.aurora)).not.toEqual(queueEnv(templates.cdc));
  });

  test('VPC flow logs have a retention period', () => {
    templates.base.hasResourceProperties('AWS::Logs::LogGroup', { RetentionInDays: Match.anyValue() });
  });
});

describe('EdgeSecurityStack', () => {
  test('WebACL is CloudFront-scoped and evaluates managed rules before the allow list', () => {
    templates.edge.hasResourceProperties('AWS::WAFv2::WebACL', {
      Scope: 'CLOUDFRONT',
      DefaultAction: { Block: {} },
    });
    const acl = Object.values(templates.edge.findResources('AWS::WAFv2::WebACL'))[0] as {
      Properties: { Rules: { Priority: number; Action?: { Allow?: object }; Statement: { ManagedRuleGroupStatement?: object } }[] };
    };
    const rules = acl.Properties.Rules;
    const allowPriority = rules.find((r) => r.Action?.Allow)!.Priority;
    const managedPriorities = rules.filter((r) => r.Statement.ManagedRuleGroupStatement).map((r) => r.Priority);
    expect(managedPriorities.length).toBeGreaterThan(0);
    for (const p of managedPriorities) {
      expect(p).toBeLessThan(allowPriority);
    }
  });

  test('is deployed to us-east-1', () => {
    expect(stacks.edge.region).toBe('us-east-1');
  });

  test('rejects an invalid CIDR, alone or in a list', () => {
    for (const allowedCidr of ['203.0.113.10', '203.0.113.10/32,nope', '', ' , ']) {
      const app = new cdk.App();
      expect(() => new EdgeSecurityStack(app, 'Bad', { env: { region: 'us-east-1' }, allowedCidr })).toThrow(
        /allowedCidr/,
      );
    }
  });

  test('accepts a comma-separated list of CIDRs', () => {
    const app = new cdk.App();
    const stack = new EdgeSecurityStack(app, 'Multi', {
      env: { region: 'us-east-1' },
      allowedCidr: '203.0.113.10/32, 203.0.113.20/32,203.0.113.10/32',
    });
    Template.fromStack(stack).hasResourceProperties('AWS::WAFv2::IPSet', {
      Addresses: ['203.0.113.10/32', '203.0.113.20/32'],
    });
  });
});

describe.each([
  ['aurora', templates.aurora],
  ['cdc', templates.cdc],
])('%s public entry point', (_name, template) => {
  test('ALB is internal and only reachable from the CloudFront prefix list', () => {
    template.allResourcesProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', { Scheme: 'internal' });
    for (const sg of Object.values(template.findResources('AWS::EC2::SecurityGroup'))) {
      for (const rule of (sg as { Properties: { SecurityGroupIngress?: { CidrIp?: string }[] } }).Properties
        .SecurityGroupIngress ?? []) {
        expect(rule.CidrIp).not.toBe('0.0.0.0/0');
      }
    }
    template.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
      FromPort: 80,
      SourcePrefixListId: Match.anyValue(),
    });
  });

  test('CloudFront enforces HTTPS, uses a VPC origin and the WAF WebACL', () => {
    template.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: Match.objectLike({
        WebACLId: Match.anyValue(),
        Logging: Match.anyValue(),
        IPV6Enabled: false,
        DefaultCacheBehavior: Match.objectLike({ ViewerProtocolPolicy: 'redirect-to-https' }),
        Origins: [Match.objectLike({ VpcOriginConfig: Match.anyValue() })],
      }),
    });
  });

  test('container runs with a read-only root filesystem and a liveness health check', () => {
    template.hasResourceProperties('AWS::ECS::TaskDefinition', {
      RuntimePlatform: { CpuArchitecture: 'ARM64' },
      ContainerDefinitions: [
        Match.objectLike({
          ReadonlyRootFilesystem: true,
          HealthCheck: Match.objectLike({ Command: Match.arrayWith([Match.stringLikeRegexp('liveness')]) }),
        }),
      ],
    });
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      HealthCheckPath: '/actuator/health/readiness',
    });
    template.hasResourceProperties('AWS::ECS::Service', {
      DeploymentConfiguration: Match.objectLike({ DeploymentCircuitBreaker: { Enable: true, Rollback: true } }),
    });
  });
});

describe('AuroraStack', () => {
  test('uses Aurora Serverless v2 (not the retired v1) with encryption and forced TLS', () => {
    templates.aurora.hasResourceProperties('AWS::RDS::DBCluster', {
      Engine: 'aurora-postgresql',
      EngineMode: Match.absent(),
      StorageEncrypted: true,
      ServerlessV2ScalingConfiguration: { MinCapacity: 0.5, MaxCapacity: 4 },
    });
    templates.aurora.hasResourceProperties('AWS::RDS::DBClusterParameterGroup', {
      Parameters: { 'rds.force_ssl': '1' },
    });
  });

  test('ECS service is only created once the database writer instance exists', () => {
    const services = templates.aurora.findResources('AWS::ECS::Service');
    const instances = Object.keys(templates.aurora.findResources('AWS::RDS::DBInstance'));
    const [service] = Object.values(services) as { DependsOn?: string[] }[];
    expect(instances).toHaveLength(1);
    expect(service.DependsOn).toEqual(expect.arrayContaining(instances));
  });

  test('database password is injected as an ECS secret, never as plain text', () => {
    templates.aurora.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [
        Match.objectLike({
          Secrets: Match.arrayWith([Match.objectLike({ Name: 'SPRING_DATASOURCE_PASSWORD' })]),
          Environment: Match.arrayWith([
            Match.objectLike({ Name: 'SPRING_DATASOURCE_URL', Value: Match.objectLike({}) }),
          ]),
        }),
      ],
    });
    templates.aurora.resourcePropertiesCountIs(
      'AWS::RDS::DBCluster',
      { MasterUserPassword: Match.stringLikeRegexp('^[^{]') },
      0,
    );
  });
});

describe('CdcStack', () => {
  test('tables have point-in-time recovery and the binder tables match Spring Cloud AWS schemas', () => {
    templates.cdc.resourceCountIs('AWS::DynamoDB::Table', 3);
    templates.cdc.allResourcesProperties('AWS::DynamoDB::Table', {
      PointInTimeRecoverySpecification: { PointInTimeRecoveryEnabled: true },
    });
    templates.cdc.hasResourceProperties('AWS::DynamoDB::Table', {
      TableName: 'flight',
      KinesisStreamSpecification: Match.anyValue(),
    });
    templates.cdc.hasResourceProperties('AWS::DynamoDB::Table', {
      KeySchema: [{ AttributeName: 'metadataKey', KeyType: 'HASH' }],
    });
    templates.cdc.hasResourceProperties('AWS::DynamoDB::Table', {
      KeySchema: [{ AttributeName: 'lockKey', KeyType: 'HASH' }],
      TimeToLiveSpecification: { AttributeName: 'expireAt', Enabled: true },
    });
  });

  test('Kinesis stream is encrypted, retains a 7-day recovery window and alarms on relay lag', () => {
    templates.cdc.hasResourceProperties('AWS::Kinesis::Stream', {
      StreamEncryption: { EncryptionType: 'KMS' },
      RetentionPeriodHours: 168,
    });
    templates.cdc.hasResourceProperties('AWS::CloudWatch::Alarm', {
      MetricName: 'GetRecords.IteratorAgeMilliseconds',
      Threshold: 3600000,
    });
    templates.cdc.hasResourceProperties('AWS::CloudWatch::Alarm', {
      MetricName: 'GetRecords.Success',
      Statistic: 'Sum',
      ComparisonOperator: 'LessThanThreshold',
      TreatMissingData: 'breaching',
    });
  });

  test('application never gets table or stream management permissions', () => {
    for (const statement of policyStatements(templates.cdc)) {
      for (const action of asArray(statement.Action)) {
        expect(action).not.toMatch(/^(dynamodb:(CreateTable|DeleteTable|UpdateTable)|kinesis:(CreateStream|DeleteStream))$/);
      }
    }
  });
});
