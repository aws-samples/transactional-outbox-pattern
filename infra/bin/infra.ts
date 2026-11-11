#!/usr/bin/env node
// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { AwsSolutionsChecks } from 'cdk-nag';
import { buildApp } from '../lib/app';

const app = new cdk.App();

const allowedCidr = app.node.tryGetContext('allowedCidr');
if (!allowedCidr) {
  throw new Error('Missing context value "allowedCidr", e.g. `cdk deploy --all -c allowedCidr=203.0.113.10/32`');
}
const account = app.node.tryGetContext('account') ?? process.env.CDK_DEFAULT_ACCOUNT;
const region = app.node.tryGetContext('region') ?? process.env.CDK_DEFAULT_REGION;
if (!region) {
  throw new Error('Unable to determine the target region: configure a default AWS region or pass `-c region=<region>`');
}

buildApp(app, { account, region, allowedCidr });

cdk.Validations.of(app).addPlugins(new AwsSolutionsChecks(app, { verbose: true }));
