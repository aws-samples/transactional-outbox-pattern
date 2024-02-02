# Transactional Outbox Pattern Sample

## Intent

The transactional outbox pattern resolves the dual write operations issue that occurs in distributed systems when a single operation involves both a database write operation and a message or event notification. A dual write operation occurs when an application writes to two different systems; for example, when a microservice needs to persist data in the database and send a message to notify other systems. A failure in one of these operations might result in inconsistent data.

Blog reference: https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html 

## Motivation

When a microservice sends an event notification after a database update, these two operations should run atomically to ensure data consistency and reliability.
- If the database update is successful but the event notification fails, the downstream service will not be aware of the change, and the system can enter an inconsistent state.
- If the database update fails but the event notification is sent, data could get corrupted, which might affect the reliability of the system.

## Applicability

Use the transactional outbox pattern when:
- You're building an event-driven application where a database update initiates an event notification .
- You want to ensure atomicity in operations that involve two services.
- You want to implement the event sourcing pattern.

## Issues and Considerations

Issues and considerations
- Duplicate messages: The events processing service might send out duplicate messages or events, so we recommend that you make the consuming service idempotent by tracking the processed messages.
- Order of notification: Send messages or events in the same order in which the service updates the database. This is critical for the event sourcing pattern where you can use an event store for point-in-time recovery of the data store. If the order is incorrect, it might compromise the quality of the data. Eventual consistency and database rollback can compound the issue if the order of notifications isn't preserved.
- Transaction rollback: Do not send out an event notification if the transaction is rolled back.
- Service-level transaction handling: If the transaction spans services that require data store updates, use the saga orchestration pattern to preserve data integrity across the data stores.

## Implementation

We will look at two ways of implementing this pattern: one leveraging a traditional relational database with an outbox table, and one leveraging a NoSQL database that natively supports real-time change data capture (CDC).

The outbox implementation leverages [Amazon Elastic Load Balancer](https://aws.amazon.com/elasticloadbalancing/), [Amazon ECS](https://aws.amazon.com/ecs/), [Amazon Aurora](https://aws.amazon.com/rds/aurora/) and [Amazon SQS](https://aws.amazon.com/sqs/) and will lead you to provision the following infrastructure:

![Infra](img/outbox-sample-infra.png)

The CDC implementation leverages [Amazon DynamoDB](https://aws.amazon.com/dynamodb/) and [Amazon Kinesis Data Streams](https://aws.amazon.com/kinesis/data-streams/) and will lead you to provision the following infrastructure:

![InfraCDC](img/outbox-sample-cdc-infra.png)

### Prerequisites

- An [AWS](https://aws.amazon.com/) account.
- An AWS user with AdministratorAccess (see the [instructions](https://console.aws.amazon.com/iam/home#/roles%24new?step=review&commonUseCase=EC2%2BEC2&selectedUseCase=EC2&policies=arn:aws:iam::aws:policy%2FAdministratorAccess) on the [AWS Identity and Access Management](http://aws.amazon.com/iam) (IAM) console).
- Access to the following AWS services: Elastic Load Balancing, Amazon ECS, Amazon Aurora, Amazon SQS, Amazon DynamoDB and Amazon Kinesis Data Streams.
- [Docker](https://docs.docker.com/engine/install/), [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) and [NodeJS](https://nodejs.org/en) installed. Docker daemon running.

### Deploy using CDK

#### Step 1: Download the application

```shell
git clone https://github.com/aws-samples/transactional-outbox-pattern.git
```

#### Step 2: Initialize your CDK environment and deploy basic resources

The `cdk.json` file tells the CDK Toolkit how to execute your app. Build and deploy the CDK code (including the application) using the commands below. Replace <MY_PUBLIC_IP> by the public IP you will use to access the ALB endpoints (you can find your public IP through websites such as https://www.whatismyip.com/).

```shell
npm install -g aws-cdk
cd transactional-outbox-pattern/infra
npm install
cdk bootstrap
cdk synth
cdk deploy BaseStack --parameters myPublicIP=<MY_PUBLIC_IP>/32
```
After about 5-10 mins, the deployment will complete.

> **NOTE:** If your Public IP changes and you lose access to the application you can update it directly in the AWS Console by nagivating to WAF & Shield, and then by clicking on IP sets in the left panel. Make sure you choose the region you deployed your stack in, and then update the IP set with your new Public IP.

### Usage

#### Outbox implementation

> Please refer to the following [README](outbox-implementation/README.md) for detailed instructions.

#### CDC implementation

> Please refer to the following [README](cdc-implementation/README.md) for detailed instructions.

#### Cleaning Up

Type the following command to destroy the rest of the resources:
```shell
cdk destroy BaseStack
```

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.