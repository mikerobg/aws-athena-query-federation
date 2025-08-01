/*-
 * #%L
 * athena-jdbc
 * %%
 * Copyright (C) 2019 Amazon Web Services
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
package com.amazonaws.athena.connectors.jdbc.connection;

import com.amazonaws.athena.connector.credentials.CredentialsProvider;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.model.ErrorDetails;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides a generic jdbc connection factory that can be used to connect to standard databases. Configures following
 * defaults if not present:
 * <ul>
 * <li>Default ports will be used for the engine if not present.</li>
 * </ul>
 */
public class GenericJdbcConnectionFactory
        implements JdbcConnectionFactory
{
    private static final Logger LOGGER = LoggerFactory.getLogger(GenericJdbcConnectionFactory.class);

    private static final String SECRET_NAME_PATTERN_STRING = "(\\$\\{[a-zA-Z0-9:/_+=.@!-]+})";
    public static final Pattern SECRET_NAME_PATTERN = Pattern.compile(SECRET_NAME_PATTERN_STRING);

    private final DatabaseConnectionInfo databaseConnectionInfo;
    private final DatabaseConnectionConfig databaseConnectionConfig;
    private final Properties jdbcProperties;
    private volatile HikariDataSource ds;

    /**
     * @param databaseConnectionConfig database connection configuration {@link DatabaseConnectionConfig}
     * @param properties JDBC connection properties.
     */
    public GenericJdbcConnectionFactory(final DatabaseConnectionConfig databaseConnectionConfig, final Map<String, String> properties, final DatabaseConnectionInfo databaseConnectionInfo)
    {
        this.databaseConnectionInfo = Validate.notNull(databaseConnectionInfo, "databaseConnectionInfo must not be null");
        this.databaseConnectionConfig = Validate.notNull(databaseConnectionConfig, "databaseEngine must not be null");

        this.jdbcProperties = new Properties();
        if (properties != null) {
            this.jdbcProperties.putAll(properties);
        }
    }

    @Override
    public Connection getConnection(final CredentialsProvider credentialsProvider)
            throws Exception
    {
        final String derivedJdbcString;
        if (credentialsProvider != null) {
            Matcher secretMatcher = SECRET_NAME_PATTERN.matcher(databaseConnectionConfig.getJdbcConnectionString());
            derivedJdbcString = secretMatcher.replaceAll(Matcher.quoteReplacement(""));

            jdbcProperties.putAll(credentialsProvider.getCredentialMap());
        }
        else {
            derivedJdbcString = databaseConnectionConfig.getJdbcConnectionString();
        }

        if (ds == null) {
            synchronized (GenericJdbcConnectionFactory.class) { // Synchronize on the class level
                if (ds == null) { // Double-check to avoid creating more than one instance
                    HikariConfig config2 = new HikariConfig();
                    config2.setDriverClassName(databaseConnectionInfo.getDriverClassName());
                    config2.setDataSourceProperties(jdbcProperties);
                    config2.setJdbcUrl(derivedJdbcString);
                    config2.setMinimumIdle(1);
                    ds = new HikariDataSource(config2);
                    LOGGER.debug("Create data source");
                }
            }
        }

        Connection connection = null;
        try {
            connection = ds.getConnection();
        }
        catch (SQLException e) {
            if (e.getMessage().contains("Name or service not known")) {
                throw new AthenaConnectorException(e.getMessage(), ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString()).build());
            }
            else if (e.getMessage().contains("Incorrect username or password was specified.")) {
                throw new AthenaConnectorException(e.getMessage(), ErrorDetails.builder().errorCode(FederationSourceErrorCode.INVALID_CREDENTIALS_EXCEPTION.toString()).build());
            }
        }

        return connection;
    }

    private String encodeValue(String value)
    {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        }
        catch (UnsupportedEncodingException ex) {
            throw new AthenaConnectorException("Unsupported Encoding Exception: ",
                    ErrorDetails.builder().errorCode(FederationSourceErrorCode.OPERATION_NOT_SUPPORTED_EXCEPTION.toString()).errorMessage(ex.getMessage()).build());
        }
    }
}
