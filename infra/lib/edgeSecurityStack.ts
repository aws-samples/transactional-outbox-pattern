// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';

export interface EdgeSecurityStackProps extends cdk.StackProps {
  /**
   * IPv4 CIDR(s) allowed to reach the sample APIs: one CIDR, or a comma-separated list for
   * networks whose egress uses several addresses, e.g. "203.0.113.10/32,203.0.113.20/32".
   */
  readonly allowedCidr: string;
}

/**
 * WAF WebACL for the CloudFront distributions. CloudFront-scoped WebACLs must be
 * created in us-east-1, so this stack is always deployed there and its ARN is
 * shared with the application stacks through cross-region references.
 */
export class EdgeSecurityStack extends cdk.Stack {
  public readonly webAclArn: string;

  constructor(scope: Construct, id: string, props: EdgeSecurityStackProps) {
    super(scope, id, props);

    const allowedCidrs = props.allowedCidr.split(',').map((cidr) => cidr.trim()).filter((cidr) => cidr.length > 0);
    const invalid = allowedCidrs.filter((cidr) => !/^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/.test(cidr));
    if (allowedCidrs.length === 0 || invalid.length > 0) {
      throw new Error(
        `allowedCidr must be one or more comma-separated IPv4 CIDRs such as 203.0.113.10/32, got "${props.allowedCidr}"`,
      );
    }

    const allowListIpSet = new wafv2.CfnIPSet(this, 'AllowListIPSet', {
      addresses: [...new Set(allowedCidrs)],
      ipAddressVersion: 'IPV4',
      scope: 'CLOUDFRONT',
    });

    const visibility = (metricName: string): wafv2.CfnWebACL.VisibilityConfigProperty => ({
      cloudWatchMetricsEnabled: true,
      metricName,
      sampledRequestsEnabled: true,
    });

    const managedRule = (name: string, priority: number): wafv2.CfnWebACL.RuleProperty => ({
      name,
      priority,
      overrideAction: { none: {} },
      statement: { managedRuleGroupStatement: { vendorName: 'AWS', name } },
      visibilityConfig: visibility(name),
    });

    // Rule order matters: "Allow" is a terminating action, so the managed rule groups
    // are evaluated first, then the IP allow list, then the default action blocks
    // everything else.
    const webAcl = new wafv2.CfnWebACL(this, 'WebAcl', {
      defaultAction: { block: {} },
      scope: 'CLOUDFRONT',
      visibilityConfig: visibility('OutboxWebAcl'),
      rules: [
        managedRule('AWSManagedRulesCommonRuleSet', 0),
        managedRule('AWSManagedRulesKnownBadInputsRuleSet', 1),
        managedRule('AWSManagedRulesAmazonIpReputationList', 2),
        {
          name: 'AllowListIPSet-Rule',
          priority: 10,
          action: { allow: {} },
          statement: { ipSetReferenceStatement: { arn: allowListIpSet.attrArn } },
          visibilityConfig: visibility('AllowListIPSet-Rule'),
        },
      ],
    });

    this.webAclArn = webAcl.attrArn;
  }
}
