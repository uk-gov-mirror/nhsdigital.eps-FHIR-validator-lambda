#!/usr/bin/env bash

echo "$COMMIT_ID"
CF_LONDON_EXPORTS=$(aws cloudformation list-exports --region eu-west-2 --output json)
ARTIFACT_BUCKET_ARN=$(echo "$CF_LONDON_EXPORTS" | \
    jq \
    --arg EXPORT_NAME "account-resources-cdk-uk:Bucket:ArtifactsBucket:Arn" \
    -r '.Exports[] | select(.Name == $EXPORT_NAME) | .Value')
ARTIFACT_BUCKET_NAME=$(echo "$ARTIFACT_BUCKET_ARN" | cut -d: -f6 | cut -d/ -f1)
if [ -z "${ARTIFACT_BUCKET_NAME}" ]; then
    echo "could not retrieve artifact_bucket from aws cloudformation list-exports"
    exit 1
fi

CLOUD_FORMATION_EXECUTION_ROLE=$(echo "$CF_LONDON_EXPORTS" | \
    jq \
    --arg EXPORT_NAME "iam-cdk:IAM:CloudFormationExecutionRole:Arn" \
    -r '.Exports[] | select(.Name == $EXPORT_NAME) | .Value')
if [ -z "${CLOUD_FORMATION_EXECUTION_ROLE}" ]; then
    echo "could not retrieve cloud_formation_execution_role from aws cloudformation list-exports"
    exit 1
fi

TRUSTSTORE_BUCKET_ARN=$(echo "$CF_LONDON_EXPORTS" | \
        jq \
        --arg EXPORT_NAME "account-resources-cdk-uk:Bucket:TrustStoreBucket:Arn" \
        -r '.Exports[] | select(.Name == $EXPORT_NAME) | .Value')
TRUSTSTORE_BUCKET_NAME=$(echo "${TRUSTSTORE_BUCKET_ARN}" | cut -d ":" -f 6)
if [ -z "${TRUSTSTORE_BUCKET_NAME}" ]; then
    echo "could not retrieve truststore_bucket from aws cloudformation list-exports"
    exit 1
fi
LATEST_TRUSTSTORE_VERSION=$(aws s3api list-object-versions --bucket "${TRUSTSTORE_BUCKET_NAME}" --prefix "${TRUSTSTORE_FILE}" --query 'Versions[?IsLatest].[VersionId]' --output text)

export LATEST_TRUSTSTORE_VERSION
export ARTIFACT_BUCKET_NAME
export CLOUD_FORMATION_EXECUTION_ROLE

cd ../../ || exit

REPO=eps-FHIR-validator-lambda
CFN_DRIFT_DETECTION_GROUP="fhir-validator"
if [[ "$STACK_NAME" =~ -pr-[0-9]+$ ]]; then
  CFN_DRIFT_DETECTION_GROUP="fhir-validator-pull-request"
fi


sam deploy \
    --template-file "$TEMPLATE_FILE" \
    --stack-name "$STACK_NAME" \
    --capabilities CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
    --region eu-west-2 \
    --s3-bucket "$ARTIFACT_BUCKET_NAME" \
    --s3-prefix "$ARTIFACT_BUCKET_PREFIX" \
    --config-file samconfig_package_and_deploy.toml \
    --no-fail-on-empty-changeset \
    --role-arn "$CLOUD_FORMATION_EXECUTION_ROLE" \
    --no-confirm-changeset \
    --force-upload \
    --tags "version=$VERSION_NUMBER stack=$STACK_NAME repo=$REPO cfnDriftDetectionGroup=$CFN_DRIFT_DETECTION_GROUP" \
    --parameter-overrides \
            EnableSplunk=true \
            LogLevel="$LOG_LEVEL" \
            LogRetentionDays="$LOG_RETENTION_DAYS" \
            EnableAlerts="$ENABLE_ALERTS"
