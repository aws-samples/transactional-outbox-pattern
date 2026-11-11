// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { AuroraStack } from './auroraStack';
import { BaseStack } from './baseStack';
import { CdcStack } from './cdcStack';
import { EdgeSecurityStack } from './edgeSecurityStack';

export interface OutboxAppConfig {
  readonly account?: string;
  readonly region: string;
  readonly allowedCidr: string;
}

export interface OutboxAppStacks {
  readonly edge: EdgeSecurityStack;
  readonly base: BaseStack;
  readonly aurora: AuroraStack;
  readonly cdc: CdcStack;
}

/** Wires all stacks of the sample. Shared by bin/infra.ts and the unit tests. */
export function buildApp(app: cdk.App, config: OutboxAppConfig): OutboxAppStacks {
  const env = { account: config.account, region: config.region };

  // CloudFront-scoped WAF must live in us-east-1.
  const edge = new EdgeSecurityStack(app, 'EdgeSecurityStack', {
    env: { account: config.account, region: 'us-east-1' },
    crossRegionReferences: true,
    allowedCidr: config.allowedCidr,
  });

  const base = new BaseStack(app, 'BaseStack', { env });

  const aurora = new AuroraStack(app, 'AuroraStack', {
    env,
    crossRegionReferences: true,
    webAclArn: edge.webAclArn,
    outboxEcsCluster: base.outboxEcsCluster,
    vpc: base.vpc,
    flightQueue: base.outboxFlightQueue,
  });

  const cdc = new CdcStack(app, 'CdcStack', {
    env,
    crossRegionReferences: true,
    webAclArn: edge.webAclArn,
    outboxEcsCluster: base.outboxEcsCluster,
    flightQueue: base.cdcFlightQueue,
  });

  cdk.Tags.of(app).add('Project', 'transactional-outbox-pattern');
  cdk.Tags.of(app).add('Environment', 'sample');
  cdk.Tags.of(app).add('ManagedBy', 'cdk');

  return { edge, base, aurora, cdc };
}
