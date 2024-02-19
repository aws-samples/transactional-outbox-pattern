#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { AuroraStack } from '../lib/auroraStack';
import { CdcStack } from '../lib/cdcStack';
import { BaseStack } from '../lib/baseStack';

const app = new cdk.App();
const baseStack = new BaseStack(app, 'BaseStack', {})
new AuroraStack(app, 'AuroraStack', {
  webACL: baseStack.webACL,
  outboxEcsCluster: baseStack.outboxEcsCluster,
  vpc: baseStack.vpc,
  flightQueue: baseStack.flightQueue,
});
new CdcStack(app, 'CdcStack', {
  webACL: baseStack.webACL,
  outboxEcsCluster: baseStack.outboxEcsCluster,
  flightQueue: baseStack.flightQueue,
});