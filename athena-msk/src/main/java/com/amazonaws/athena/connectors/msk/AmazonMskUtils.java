/*-
 * #%L
 * athena-msk
 * %%
 * Copyright (C) 2019 - 2022 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.msk;

import com.amazonaws.athena.connectors.msk.dto.Message;
import com.amazonaws.athena.connectors.msk.dto.SplitParameters;
import com.amazonaws.athena.connectors.msk.dto.TopicResultSet;
import com.amazonaws.athena.connectors.msk.serde.MskCsvDeserializer;
import com.amazonaws.athena.connectors.msk.serde.MskJsonDeserializer;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import com.amazonaws.services.schemaregistry.utils.AvroRecordType;
import com.amazonaws.services.schemaregistry.utils.ProtobufMessageType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DynamicMessage;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class AmazonMskUtils
{
    // Parameters.AuthType.AllowedValues from athena-msk.yaml
    enum AuthType {
      SASL_SSL_AWS_MSK_IAM,
      SASL_SSL_SCRAM_SHA512,
      SASL_SSL_PLAIN,
      SASL_PLAINTEXT_PLAIN,
      SSL,
      NO_AUTH
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AmazonMskUtils.class);

    private static final String KEYSTORE = "kafka.client.keystore.jks";
    private static final String TRUSTSTORE = "kafka.client.truststore.jks";
    static final String KAFKA_SECURITY_PROTOCOL = "security.protocol";
    static final String KAFKA_SSL_CLIENT_AUTH = "ssl.client.auth";
    static final String KAFKA_SSL_KEY_PASSWORD = "ssl.key.password";
    static final String KAFKA_KEYSTORE_LOCATION = "ssl.keystore.location";
    static final String KAFKA_KEYSTORE_PASSWORD = "ssl.keystore.password";
    static final String KAFKA_TRUSTSTORE_LOCATION = "ssl.truststore.location";
    static final String KAFKA_TRUSTSTORE_PASSWORD = "ssl.truststore.password";

    private static final String KAFKA_SASL_JAAS_CONFIG = "sasl.jaas.config";
    private static final String KAFKA_SASL_MECHANISM = "sasl.mechanism";
    private static final String KAFKA_SASL_CLIENT_CALLBACK_HANDLER_CLASS = "sasl.client.callback.handler.class";
    private static final String KAFKA_BOOTSTRAP_SERVERS_CONFIG = "bootstrap.servers";
    private static final String KAFKA_GROUP_ID_CONFIG = "group.id";
    private static final String KAFKA_EXCLUDE_INTERNAL_TOPICS_CONFIG = "exclude.internal.topics";
    private static final String KAFKA_ENABLE_AUTO_COMMIT_CONFIG = "enable.auto.commit";
    private static final String KAFKA_AUTO_OFFSET_RESET_CONFIG = "auto.offset.reset";
    private static final String KAFKA_MAX_POLL_RECORDS_CONFIG = "max.poll.records";
    private static final String KAFKA_MAX_PARTITION_FETCH_BYTES_CONFIG = "max.partition.fetch.bytes";
    private static final String KAFKA_KEY_DESERIALIZER_CLASS_CONFIG = "key.deserializer";
    private static final String KAFKA_VALUE_DESERIALIZER_CLASS_CONFIG = "value.deserializer";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private AmazonMskUtils() {}

    /**
     * Creates Kafka consumer instance.
     *
     * @return @return {@link KafkaConsumer}
     */
    public static Consumer<String, String> getKafkaConsumer(java.util.Map<String, String> configOptions) throws Exception
    {
        Properties properties;
        properties = getKafkaProperties(configOptions);
        properties.setProperty(KAFKA_VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        return new KafkaConsumer<>(properties);
    }

    /**
     * Creates instance of Kafka consumer.
     * In the properties we have to specify the Deserializer type,
     * we are supporting only JSON or CSV data from kafka topic. Therefor,
     * to transform topic data into pojo we need to specify Deserializer type to KafkaConsumer.
     * Schema metadata can tell use about the topic data type i.e. dataFormat = json | csv.
     *
     * @param schema - instance of {@link Schema}
     * @return Consumer {@link Consumer}
     * @throws Exception - {@link Exception}
     */
    public static Consumer<String, TopicResultSet> getKafkaConsumer(Schema schema, java.util.Map<String, String> configOptions) throws Exception
    {
        Properties properties = AmazonMskUtils.getKafkaProperties(configOptions);
        properties.setProperty(KAFKA_VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // Get the topic data type, while we had built the schema we had put it in schema's metadata
        String dataFormat = schema.getCustomMetadata().get("dataFormat");

        // Based on topic data type we should select Deserializer to be attached to KafkaConsumer
        Deserializer<TopicResultSet> valueDeserializer;
        if (dataFormat.equals(Message.DATA_FORMAT_JSON)) {
            valueDeserializer = new MskJsonDeserializer(schema);
        }
        else if (dataFormat.equals(Message.DATA_FORMAT_CSV)) {
            valueDeserializer = new MskCsvDeserializer(schema);
        }
        else {
            throw new Exception("Unsupported Format provided" + dataFormat);
        }

        return new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                valueDeserializer
        );
    }
    public static Consumer<String, GenericRecord> getAvroKafkaConsumer(java.util.Map<String, String> configOptions) throws Exception
    {
        Properties properties = getKafkaProperties(configOptions);
        properties.put(KAFKA_VALUE_DESERIALIZER_CLASS_CONFIG, GlueSchemaRegistryKafkaDeserializer.class
                .getName());
        properties.put(AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, AvroRecordType.GENERIC_RECORD.getName());
        return new KafkaConsumer<>(properties);
    }

    public static Consumer<String, DynamicMessage> getProtobufKafkaConsumer(java.util.Map<String, String> configOptions) throws Exception
    {
        Properties properties = getKafkaProperties(configOptions);
        properties.put(KAFKA_VALUE_DESERIALIZER_CLASS_CONFIG, GlueSchemaRegistryKafkaDeserializer.class.getName());
        properties.put(AWSSchemaRegistryConstants.PROTOBUF_MESSAGE_TYPE, ProtobufMessageType.DYNAMIC_MESSAGE.getName());
        return new KafkaConsumer<>(properties);
    }

    /**
     * Creates the required settings for kafka consumer.
     *
     * @return {@link Properties}
     * @throws Exception - {@link Exception}
     */
    public static Properties getKafkaProperties(java.util.Map<String, String> configOptions) throws Exception
    {
        // Create the necessary properties to use for kafka connection
        Properties properties = new Properties();
        properties.setProperty(KAFKA_BOOTSTRAP_SERVERS_CONFIG, getRequiredConfig(AmazonMskConstants.ENV_KAFKA_ENDPOINT, configOptions));
        properties.setProperty(KAFKA_GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.setProperty(KAFKA_EXCLUDE_INTERNAL_TOPICS_CONFIG, "true");
        properties.setProperty(KAFKA_ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.setProperty(KAFKA_AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(KAFKA_MAX_POLL_RECORDS_CONFIG, "10000");
        properties.setProperty(KAFKA_MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576");
        properties.setProperty(KAFKA_KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        //fetch authentication type for the kafka cluster
        AuthType authType = AuthType.valueOf(getRequiredConfig(AmazonMskConstants.AUTH_TYPE, configOptions).toUpperCase().trim());

        switch (authType) {
            case SASL_SSL_AWS_MSK_IAM:
                setIAMAuthKafkaProperties(properties);
                break;
            case SASL_SSL_SCRAM_SHA512:
                setScramAuthKafkaProperties(properties, configOptions);
                break;
            case SASL_SSL_PLAIN:
                setSaslSslAuthKafkaProperties(properties, configOptions);
                break;
            case SASL_PLAINTEXT_PLAIN:
                setSaslPlainAuthKafkaProperties(properties, configOptions);
                break;
            case SSL:
                setSSLAuthKafkaProperties(properties, configOptions);
                break;
            case NO_AUTH:
                break;
            default:
                LOGGER.error("Unsupported Authentication type {}", authType);
                throw new RuntimeException("Unsupported Authentication type" + authType);
        }
        return properties;
    }

    /**
     * Creates the required SSL based settings for kafka consumer.
     *
     * @param properties - common properties for kafka consumer
     * @return {@link Properties}
     * @throws Exception - {@link Exception}
     */
    protected static Properties setSSLAuthKafkaProperties(Properties properties, java.util.Map<String, String> configOptions) throws Exception
    {
        // Download certificates for kafka connection from S3 and save to temp directory
        Path tempDir = copyCertificatesFromS3ToTempFolder(configOptions);

        // Fetch the secrets for kafka connection from AWS SecretManager and set required kafka properties for
        //establishing successful connection
        Map<String, Object> secretInfo = getCredentialsAsKeyValue(configOptions);
        properties.setProperty(KAFKA_SECURITY_PROTOCOL, "SSL");
        properties.setProperty(KAFKA_SSL_CLIENT_AUTH, "required");
        properties.setProperty(KAFKA_SSL_KEY_PASSWORD, secretInfo.get(AmazonMskConstants.SSL_KEY_PASSWORD).toString());
        properties.setProperty(KAFKA_KEYSTORE_LOCATION, tempDir + File.separator + KEYSTORE);
        properties.setProperty(KAFKA_KEYSTORE_PASSWORD, secretInfo.get(AmazonMskConstants.KEYSTORE_PASSWORD).toString());
        properties.setProperty(KAFKA_TRUSTSTORE_LOCATION, tempDir + File.separator + TRUSTSTORE);
        properties.setProperty(KAFKA_TRUSTSTORE_PASSWORD, secretInfo.get(AmazonMskConstants.TRUSTSTORE_PASSWORD).toString());
        return properties;
    }

    /**
     * Creates the required IAM based settings for kafka consumer.
     *
     * @param properties - IAM specific properties for kafka consumer
     * @return {@link Properties}
     */
    protected static Properties setIAMAuthKafkaProperties(Properties properties)
    {
        properties.setProperty(KAFKA_SECURITY_PROTOCOL, "SASL_SSL");
        properties.setProperty(KAFKA_SASL_MECHANISM, "AWS_MSK_IAM");
        properties.setProperty(KAFKA_SASL_JAAS_CONFIG, "software.amazon.msk.auth.iam.IAMLoginModule required;");
        properties.setProperty(KAFKA_SASL_CLIENT_CALLBACK_HANDLER_CLASS, "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return properties;
    }

    /**
     * Creates the required SASL based settings for kafka consumer.
     *
     * @param properties - SASL/SCRAM properties for kafka consumer
     * @return {@link Properties}
     * @throws Exception - {@link Exception}
     */
    protected static Properties setScramAuthKafkaProperties(Properties properties, java.util.Map<String, String> configOptions) throws Exception
    {
        properties.setProperty(KAFKA_SECURITY_PROTOCOL, "SASL_SSL");
        properties.setProperty(KAFKA_SASL_MECHANISM, "SCRAM-SHA-512");
        Map<String, Object> cred = getCredentialsAsKeyValue(configOptions);
        String username = cred.get(AmazonMskConstants.AWS_SECRET_USERNAME).toString();
        String password = cred.get(AmazonMskConstants.AWS_SECRET_PWD).toString();
        properties.put(KAFKA_SASL_JAAS_CONFIG, "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"" + username + "\" password=\"" + password + "\";");
        return properties;
    }

    /**
     * Creates the required SASL based settings for kafka consumer.
     *
     * @param properties - SASL/PLAINTEXT properties for kafka consumer
     * @return {@link Properties}
     * @throws Exception - {@link Exception}
     */
    protected static Properties setSaslPlainAuthKafkaProperties(Properties properties, java.util.Map<String, String> configOptions) throws Exception
    {
        properties.setProperty(KAFKA_SECURITY_PROTOCOL, "SASL_PLAINTEXT");
        properties.setProperty(KAFKA_SASL_MECHANISM, "PLAIN");
        Map<String, Object> cred = getCredentialsAsKeyValue(configOptions);
        properties.put(KAFKA_SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + cred.get(AmazonMskConstants.AWS_SECRET_USERNAME).toString() + "\" password=\"" + cred.get(AmazonMskConstants.AWS_SECRET_PWD).toString() + "\";");
        return properties;
    }
    /**
     * Creates the required SASL based settings for kafka consumer.
     *
     * @param properties - SASL/SSL properties for kafka consumer
     * @return {@link Properties}
     * @throws Exception - {@link Exception}
     */
    protected static Properties setSaslSslAuthKafkaProperties(Properties properties, java.util.Map<String, String> configOptions) throws Exception
    {
        Map<String, Object> cred = getCredentialsAsKeyValue(configOptions);
        properties.setProperty(KAFKA_SECURITY_PROTOCOL, "SASL_SSL");
        properties.setProperty(KAFKA_SASL_MECHANISM, "PLAIN");
        String s3uri = configOptions.get(AmazonMskConstants.CERTIFICATES_S3_REFERENCE);
        if (StringUtils.isNotBlank(s3uri)) {
            //Download certificates for kafka connection from S3 and save to temp directory
            Path tempDir = copyCertificatesFromS3ToTempFolder(configOptions);
            properties.setProperty(KAFKA_TRUSTSTORE_LOCATION, tempDir + File.separator + TRUSTSTORE);
            properties.setProperty(KAFKA_TRUSTSTORE_PASSWORD, cred.get(AmazonMskConstants.TRUSTSTORE_PASSWORD).toString());
        }
        properties.put(KAFKA_SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + cred.get(AmazonMskConstants.AWS_SECRET_USERNAME).toString() + "\" password=\"" + cred.get(AmazonMskConstants.AWS_SECRET_PWD).toString() + "\";");
        return properties;
    }

    /**
     * Downloads the truststore and keystore certificates from S3 to temp directory.
     *
     * @throws Exception - {@link Exception}
     */
    protected static Path copyCertificatesFromS3ToTempFolder(java.util.Map<String, String> configOptions) throws Exception
    {
        LOGGER.debug("Creating the connection with AWS S3 for copying certificates to Temp Folder");
        Path tempDir = getTempDirPath();
        // create() Uses default credentials chain
        S3Client s3Client = S3Client.create();

        String s3uri = getRequiredConfig(AmazonMskConstants.CERTIFICATES_S3_REFERENCE, configOptions);
        String[] s3Bucket = s3uri.split("s3://")[1].split("/");

        ListObjectsResponse response = s3Client.listObjects(ListObjectsRequest.builder()
                .bucket(s3Bucket[0])
                .prefix(s3Bucket[1])
                .build());

        for (S3Object objectSummary : response.contents()) {
            ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(s3Bucket[0])
                    .key(objectSummary.key())
                    .build());
            InputStream inputStream = new BufferedInputStream(responseStream);
            String key = objectSummary.key();
            String fName = key.substring(key.indexOf('/') + 1);
            if (!fName.isEmpty()) {
                File file = new File(tempDir + File.separator + fName);
                Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return tempDir;
    }

    /**
     * Creates the temp directory to put the kafka certificates.
     *
     * @return {@link Path} Temp directory path
     */
    private static Path getTempDirPath()
    {
        Path tmpPath = Paths.get(AmazonMskConstants.TEMP_DIR).toAbsolutePath();
        File filePath = new File(tmpPath + File.separator + "MSKData");
        if (filePath.exists()) {
            return filePath.toPath();
        }
        boolean isCreated = filePath.mkdirs();
        LOGGER.info("Is new directory created? " + isCreated);
        return filePath.toPath();
    }

    /**
     * Retrieve credentials from AWS Secret Manager.
     *
     * @return Map of Credentials from AWS Secret Manager
     * @throws Exception - {@link Exception}
     */
    private static Map<String, Object> getCredentialsAsKeyValue(java.util.Map<String, String> configOptions) throws Exception
    {
        SecretsManagerClient secretsManager = SecretsManagerClient.create();
        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId(getRequiredConfig(AmazonMskConstants.SECRET_MANAGER_MSK_CREDS_NAME, configOptions))
                .build();
        GetSecretValueResponse response = secretsManager.getSecretValue(getSecretValueRequest);
        return objectMapper.readValue(response.secretString(), new TypeReference<Map<String, Object>>()
        {
        });
    }

    /**
     * Gets the environment variable.
     *
     * @param key - the config key
     * @return {@link String}
     */
    private static String getRequiredConfig(String key, java.util.Map<String, String> configOptions)
    {
        String value = configOptions.getOrDefault(key, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Lambda Environment Variable " + key + " has not been populated! ");
        }
        return value;
    }

    /**
     * Translates Split parameters as readable pojo format.
     *
     * @param params - the properties for split object
     * @return {@link SplitParameters}
     */
    public static SplitParameters createSplitParam(Map<String, String> params)
    {
        String topic = params.get(SplitParameters.TOPIC);
        int partition = Integer.parseInt(params.get(SplitParameters.PARTITION));
        long startOffset = Long.parseLong(params.get(SplitParameters.START_OFFSET));
        long endOffset = Long.parseLong(params.get(SplitParameters.END_OFFSET));
        return new SplitParameters(topic, partition, startOffset, endOffset);
    }

    /**
     * Converts string data type name to ArrowType.
     * After pulling schema from glue schema registry we use this method
     * to convert the data type in glue schema to ArrowType.
     *
     * @param dataType - the column name of schema
     * @return {@link ArrowType}
     */
    public static ArrowType toArrowType(String dataType)
    {
        switch (dataType.trim().toUpperCase()) {
            case "BOOLEAN":
            case "BOOL":
                return new ArrowType.Bool();
            case "TINYINT":
                return Types.MinorType.TINYINT.getType();
            case "SMALLINT":
                return Types.MinorType.SMALLINT.getType();
            case "INT":
            case "INT32":
            case "INTEGER":
                return Types.MinorType.INT.getType();
            case "LONG":
            case "BIGINT":
            case "INT64":
                return Types.MinorType.BIGINT.getType();
            case "FLOAT":
                return Types.MinorType.FLOAT4.getType();
            case "DOUBLE":
            case "DECIMAL":
                return Types.MinorType.FLOAT8.getType();
            case "DATE":
                return Types.MinorType.DATEDAY.getType();
            case "TIMESTAMP":
                return Types.MinorType.DATEMILLI.getType();
            default:
                return Types.MinorType.VARCHAR.getType();
        }
    }
}
