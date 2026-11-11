# Infrastructure (AWS CDK, TypeScript)

| Stack | Region | Contents |
|---|---|---|
| `EdgeSecurityStack` | `us-east-1` | WAF web ACL for CloudFront: AWS managed rule groups, then the `allowedCidr` IP allow list; everything else is blocked. |
| `BaseStack` | your Region | VPC (flow logs), ECS cluster, one SQS FIFO queue + DLQ per implementation. |
| `AuroraStack` | your Region | Outbox implementation: Aurora PostgreSQL Serverless v2, Fargate service, internal ALB, CloudFront distribution. |
| `CdcStack` | your Region | CDC implementation: DynamoDB table with Kinesis Data Streams, binder state tables, Fargate service, internal ALB, CloudFront distribution. |

The internal ALBs only accept traffic from CloudFront (VPC origin + CloudFront origin-facing prefix list). [cdk-nag](https://github.com/cdklabs/cdk-nag) `AwsSolutions` checks run on every synth; each acknowledged rule carries its justification in the code.

Every command needs the allowed IPv4 CIDR, e.g. `-c allowedCidr=203.0.113.10/32`.

## Useful commands

* `npm run build`   type-check the TypeScript code
* `npm test`        run the unit tests (template assertions + cdk-nag)
* `npx cdk synth -c allowedCidr=...`   emit the synthesized CloudFormation templates
* `npx cdk diff -c allowedCidr=...`    compare deployed stacks with the current state
* `npx cdk deploy <stack> -c allowedCidr=...`   deploy a stack
