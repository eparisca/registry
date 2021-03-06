/*
 * Copyright 2016 Hortonworks.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.factory;

/**
 *
 */

import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.hortonworks.registries.common.Schema;
import com.hortonworks.registries.storage.OrderByField;
import com.hortonworks.registries.storage.Storable;
import com.hortonworks.registries.storage.StorableKey;
import com.hortonworks.registries.storage.impl.jdbc.config.ExecutionConfig;
import com.hortonworks.registries.storage.impl.jdbc.connection.ConnectionBuilder;
import com.hortonworks.registries.storage.impl.jdbc.connection.HikariCPConnectionBuilder;
import com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.query.PostgresqlDeleteQuery;
import com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.query.PostgresqlInsertQuery;
import com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.query.PostgresqlInsertUpdateDuplicate;
import com.hortonworks.registries.storage.impl.jdbc.provider.postgresql.query.PostgresqlSelectQuery;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.factory.AbstractQueryExecutor;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.query.SqlQuery;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.statement.PreparedStatementBuilder;
import com.hortonworks.registries.storage.impl.jdbc.util.Util;
import com.hortonworks.registries.storage.search.SearchQuery;
import com.zaxxer.hikari.HikariConfig;

import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * SQL query executor for PostgreSQL
 * <p>
 * To issue the new ID to insert and get auto issued key in concurrent manner, PostgresExecutor utilizes Postgres's
 * SERIAL feature
 * <p>
 * If the value of id is null, we let Postgres issue new ID and get the new ID. If the value of id is not null, we just use that value.
 */
public class PostgresqlExecutor extends AbstractQueryExecutor {

    /**
     * @param config            Object that contains arbitrary configuration that may be needed for any of the steps of the query execution process
     * @param connectionBuilder Object that establishes the connection to the database
     */
    public PostgresqlExecutor(ExecutionConfig config, ConnectionBuilder connectionBuilder) {
        super(config, connectionBuilder);
    }

    /**
     * @param config            Object that contains arbitrary configuration that may be needed for any of the steps of the query execution process
     * @param connectionBuilder Object that establishes the connection to the database
     * @param cacheBuilder      Guava cache configuration. The maximum number of entries in cache (open connections)
     *                          must not exceed the maximum number of open database connections allowed
     */
    public PostgresqlExecutor(ExecutionConfig config, ConnectionBuilder connectionBuilder, CacheBuilder<SqlQuery, PreparedStatementBuilder> cacheBuilder) {
        super(config, connectionBuilder, cacheBuilder);
    }

    // ============= Public API methods =============

    @Override
    public void insert(Storable storable) {
        insertOrUpdateWithUniqueId(storable, new PostgresqlInsertQuery(storable));
    }

    @Override
    public void insertOrUpdate(final Storable storable) {
        insertOrUpdateWithUniqueId(storable, new PostgresqlInsertUpdateDuplicate(storable));
    }

    @Override
    public <T extends Storable> Collection<T> select(String namespace) {
        return executeQuery(namespace, new PostgresqlSelectQuery(namespace));
    }

    @Override
    public <T extends Storable> Collection<T> select(String namespace, List<OrderByField> orderByFields) {
        return executeQuery(namespace, new PostgresqlSelectQuery(namespace, orderByFields));
    }

    @Override
    public <T extends Storable> Collection<T> select(StorableKey storableKey) {
        return executeQuery(storableKey.getNameSpace(), new PostgresqlSelectQuery(storableKey));
    }

    @Override
    public <T extends Storable> Collection<T> select(StorableKey storableKey, List<OrderByField> orderByFields) {
        return executeQuery(storableKey.getNameSpace(), new PostgresqlSelectQuery(storableKey, orderByFields));
    }

    @Override
    public void delete(StorableKey storableKey) {
        executeUpdate(new PostgresqlDeleteQuery(storableKey));
    }

    @Override
    public Long nextId(String namespace) {
        // We intentionally return null. Please refer the class javadoc for more details.
        return null;
    }

    @Override
    public <T extends Storable> Collection<T> select(SearchQuery searchQuery) {
        Schema schema = storableFactory.create(searchQuery.getNameSpace()).getSchema();
        return executeQuery(searchQuery.getNameSpace(), new PostgresqlSelectQuery(searchQuery, schema));
    }

    public static PostgresqlExecutor createExecutor(Map<String, Object> jdbcProps) {
        Util.validateJDBCProperties(jdbcProps, Lists.newArrayList("dataSourceClassName", "dataSource.url"));

        String dataSourceClassName = (String) jdbcProps.get("dataSourceClassName");
        log.info("data source class: [{}]", dataSourceClassName);

        String jdbcUrl = (String) jdbcProps.get("dataSource.url");
        log.info("dataSource.url is: [{}] ", jdbcUrl);

        int queryTimeOutInSecs = -1;
        if (jdbcProps.containsKey("queryTimeoutInSecs")) {
            queryTimeOutInSecs = (Integer) jdbcProps.get("queryTimeoutInSecs");
            if (queryTimeOutInSecs < 0) {
                throw new IllegalArgumentException("queryTimeoutInSecs property can not be negative");
            }
        }

        Properties properties = new Properties();
        properties.putAll(jdbcProps);
        HikariConfig hikariConfig = new HikariConfig(properties);

        HikariCPConnectionBuilder connectionBuilder = new HikariCPConnectionBuilder(hikariConfig);
        ExecutionConfig executionConfig = new ExecutionConfig(queryTimeOutInSecs);
        return new PostgresqlExecutor(executionConfig, connectionBuilder);
    }

    // this is required since the Id type in Storable is long and Postgres supports Int type for SERIAL (auto increment) field
    @Override
    protected QueryExecution getQueryExecution(SqlQuery sqlQuery) {
        return new QueryExecution(sqlQuery) {
            @Override
            protected List<Map<String, Object>> getMapsFromResultSet(ResultSet resultSet) {
                List<Map<String, Object>> res = super.getMapsFromResultSet(resultSet);
                if (res != null) {
                    res.forEach(m -> {
                        Object id = m.get("id");
                        if (id != null && id instanceof Integer) {
                            m.put("id", Long.valueOf((Integer) id));
                        }
                    });
                }
                return res;
            }
        };
    }

    private void insertOrUpdateWithUniqueId(final Storable storable, final SqlQuery sqlQuery) {
        try {
            Long id = storable.getId();
            if (id == null) {
                id = executeUpdateWithReturningGeneratedKey(sqlQuery);
                log.debug("after executeUpdate, generated id {}", id);
                storable.setId(id);
            } else {
                executeUpdate(sqlQuery);
            }
        } catch (Exception e) {
            executeUpdate(sqlQuery);
        }
    }


}