/*
 * Copyright (C) 2017-2021 Frode Randers
 * All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.gautelis.muprocessmanager;

import org.gautelis.vopn.db.Database;
import org.gautelis.vopn.db.DatabaseException;
import org.gautelis.vopn.db.utils.Derby;
import org.gautelis.vopn.db.utils.Manager;
import org.gautelis.vopn.db.utils.Options;
import org.gautelis.vopn.lang.ConfigurationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Properties;

/**
 * Creates a micro-process manager.
 */
public class MuProcessManagerFactory {
    private static final Logger log = LoggerFactory.getLogger(MuProcessManagerFactory.class);

    private static final boolean DEBUG_DATABASE_SETUP = false;

    /**
     * Returns a {@link MuProcessManager} that uses an external database for persisting process information.
     *
     * @param dataSource    a datasource for an external database.
     * @param sqlStatements a lookup table containing SQL statements, see <a href="file:doc-files/sql-statements.html">SQL statements reference</a>.
     * @param policy        the process management policy for the operation.
     * @return {@link MuProcessManager}
     */
    public static MuProcessManager getManager(
            DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(sqlStatements, "sqlStatements");
        Objects.requireNonNull(policy, "policy");

        return new MuProcessManager(
                new MuSynchronousManagerImpl(dataSource, sqlStatements, policy),
                new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy)
        );
    }

    /**
     * Returns a {@link MuProcessManager} that uses an external database for persisting process information.
     * Will use default SQL statements and policy.
     *
     * @param dataSource a datasource for an external database
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to load default SQL statements or policy
     */
    public static MuProcessManager getManager(DataSource dataSource) throws MuProcessException {
        return getManager(dataSource, getDefaultSqlStatements(), getDefaultManagementPolicy());
    }

    /**
     * Returns a {@link MuProcessManager} that internally uses an embedded Apache Derby database
     * for persisting process information. Appropriate during development and non-critical operation,
     * but consider using {@link MuProcessManagerFactory#getManager(DataSource, Properties, MuProcessManagementPolicy)} instead.
     *
     * @return {@link MuProcessManager}
     * @throws MuProcessException if failing to prepare local database.
     */
    public static MuProcessManager getManager() throws MuProcessException {
        try {
            Database.Configuration databaseConfiguration = getDefaultDatabaseConfiguration();
            DataSource dataSource = Derby.getDataSource("mu_process_manager", databaseConfiguration);
            prepareInternalDatabase(dataSource);

            return getManager(dataSource);

        } catch (DatabaseException de) {
            String info = "Failed to create data source: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);

        } catch (MuProcessException mpe) {
            String info = "Failed to create process manager: No embedded database configuration: ";
            info += mpe.getMessage();
            throw new MuProcessException(info, mpe);
        }
    }


    /*
     * Synchronous process manager
     */

    /**
     * Returns a {@link MuSynchronousManager} that uses an external database for persisting process information.
     *
     * @param dataSource    a datasource for an external database.
     * @param sqlStatements a lookup table containing SQL statements, see <a href="file:doc-files/sql-statements.html">SQL statements reference</a>.
     * @param policy        the process management policy for the operation.
     * @return {@link MuSynchronousManager}
     */
    public static MuSynchronousManager getSynchronousManager(
            DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy
    ) {
        return new MuSynchronousManagerImpl(dataSource, sqlStatements, policy);
    }

    /**
     * Returns a {@link MuSynchronousManager} that uses an external database for persisting process information.
     * Will use default SQL statements and policy.
     *
     * @param dataSource a datasource for an external database
     * @return {@link MuSynchronousManager}
     * @throws MuProcessException if failing to load default SQL statements or policy
     */
    public static MuSynchronousManager getSynchronousManager(DataSource dataSource) throws MuProcessException {
        return getSynchronousManager(dataSource, getDefaultSqlStatements(), getDefaultManagementPolicy());
    }

    /**
     * Returns a {@link MuSynchronousManager} that internally uses an embedded Apache Derby database
     * for persisting process information. Appropriate during development and non-critical operation,
     * but consider using {@link MuProcessManagerFactory#getSynchronousManager(DataSource, Properties, MuProcessManagementPolicy)} instead.
     *
     * @return {@link MuSynchronousManager}
     * @throws MuProcessException if failing to prepare local database.
     */
    public static MuSynchronousManager getSynchronousManager() throws MuProcessException {
        try {
            Database.Configuration databaseConfiguration = getDefaultDatabaseConfiguration();
            DataSource dataSource = Derby.getDataSource("mu_process_manager", databaseConfiguration);
            prepareInternalDatabase(dataSource);

            return getSynchronousManager(dataSource);

        } catch (DatabaseException de) {
            String info = "Failed to create data source: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);

        } catch (MuProcessException mpe) {
            String info = "Failed to create process manager: No embedded database configuration: ";
            info += mpe.getMessage();
            throw new MuProcessException(info, mpe);
        }
    }

    /*
     * Asynchronous process manager
     */

    /**
     * Returns a {@link MuAsynchronousManager} that uses an external database for persisting process information.
     *
     * @param dataSource    a datasource for an external database.
     * @param sqlStatements a lookup table containing SQL statements, see <a href="file:doc-files/sql-statements.html">SQL statements reference</a>.
     * @param policy        the process management policy for the operation.
     * @return {@link MuAsynchronousManager}
     */
    public static MuAsynchronousManager getAsynchronousManager(
            DataSource dataSource, Properties sqlStatements, MuProcessManagementPolicy policy
    ) {
        return new MuAsynchronousManagerImpl(dataSource, sqlStatements, policy);
    }

    /**
     * Returns a {@link MuAsynchronousManager} that uses an external database for persisting process information.
     * Will use default SQL statements and policy.
     *
     * @param dataSource a datasource for an external database
     * @return {@link MuAsynchronousManager}
     * @throws MuProcessException if failing to load default SQL statements or policy
     */
    public static MuAsynchronousManager getAsynchronousManager(DataSource dataSource) throws MuProcessException {
        return getAsynchronousManager(dataSource, getDefaultSqlStatements(), getDefaultManagementPolicy());
    }

    /**
     * Returns a {@link MuAsynchronousManager} that internally uses an embedded Apache Derby database
     * for persisting process information. Appropriate during development and non-critical operation,
     * but consider using {@link MuProcessManagerFactory#getAsynchronousManager(DataSource, Properties, MuProcessManagementPolicy)} instead.
     *
     * @return {@link MuAsynchronousManager}
     * @throws MuProcessException if failing to prepare local database.
     */
    public static MuAsynchronousManager getAsynchronousManager() throws MuProcessException {
        try {
            Database.Configuration databaseConfiguration = getDefaultDatabaseConfiguration();
            DataSource dataSource = Derby.getDataSource("mu_process_manager", databaseConfiguration);
            prepareInternalDatabase(dataSource);

            return getAsynchronousManager(dataSource);

        } catch (DatabaseException de) {
            String info = "Failed to create data source: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);

        } catch (MuProcessException mpe) {
            String info = "Failed to create process manager: No embedded database configuration: ";
            info += mpe.getMessage();
            throw new MuProcessException(info, mpe);

        }
    }


    /*
     * Helper functionality
     */


    public static void prepareInternalDatabase(DataSource dataSource) throws MuProcessException {
        Objects.requireNonNull(dataSource, "dataSource");

        try {
            Options options = Options.getDefault();
            options.debug = DEBUG_DATABASE_SETUP;
            Manager instance = new Derby(dataSource, options);

            create(instance, new PrintWriter(System.out));

        } catch (Throwable t) {
            String info = "Failed to prepare internal database: ";
            info += t.getMessage();
            throw new MuProcessException(info, t);
        }
    }

    /**
     * Creates the database objects (tables, etc).
     * <p>
     *
     * @throws Exception if fails to load configuration or fails to create database objects
     */
    private static void create(Manager manager, PrintWriter out) throws Exception {
        try (InputStream is = MuSynchronousManagerImpl.class.getResourceAsStream("default-database-create.sql")) {
            manager.execute("default-database-create.sql", new InputStreamReader(is), out);
        }
    }

    public static Database.Configuration getDatabaseConfiguration(File file) throws FileNotFoundException, MuProcessException {
        Objects.requireNonNull(file, "file");

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = Files.newInputStream(file.toPath())) {
            Properties properties = new Properties();
            properties.loadFromXML(is);
            return Database.getConfiguration(properties);
        }
        catch (IOException ioe) {
            String info = "Failed to load database configuration from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Database.Configuration getDatabaseConfiguration(Class clazz, String resource) throws MuProcessException {
        Objects.requireNonNull(clazz, "clazz");

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            Properties properties = new Properties();
            properties.loadFromXML(is);
            return Database.getConfiguration(properties);

        } catch (IOException ioe) {
            String info = "Failed to load database configuration: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Database.Configuration getDefaultDatabaseConfiguration() throws MuProcessException {
        return getDatabaseConfiguration(MuSynchronousManagerImpl.class, "default-database-configuration.xml");
    }

    public static DataSource getDefaultDataSource(String applicationName) throws MuProcessException {
        Database.Configuration defaultDatabaseConfiguration = getDefaultDatabaseConfiguration();
        try  {
            return Derby.getDataSource(applicationName, defaultDatabaseConfiguration);

        } catch (DatabaseException de) {
            String info = "Failed to establish internal datasource: ";
            info += de.getMessage();
            throw new MuProcessException(info, de);
        }
    }

    public static Properties getSqlStatements(File file) throws FileNotFoundException, MuProcessException {
        Objects.requireNonNull(file, "file");

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = Files.newInputStream(file.toPath())) {
            final Properties sqlStatements = new Properties();
            sqlStatements.loadFromXML(is);
            return sqlStatements;
        }
        catch (IOException ioe) {
            String info = "Failed to load SQL statements from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Properties getSqlStatements(Class clazz, String resource) throws MuProcessException {
        Objects.requireNonNull(clazz, "clazz");

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            final Properties sqlStatements = new Properties();
            sqlStatements.loadFromXML(is);
            return sqlStatements;
        }
        catch (IOException ioe) {
            String info = "Failed to load SQL statements: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static Properties getDefaultSqlStatements() throws MuProcessException {
        return getSqlStatements(MuSynchronousManagerImpl.class, "sql-statements.xml");
    }

    public static MuProcessManagementPolicy getManagementPolicy(File file) throws FileNotFoundException, MuProcessException {
        Objects.requireNonNull(file, "file");

        if (!file.exists() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        try (InputStream is = Files.newInputStream(file.toPath())) {
            final Properties policyProperties = new Properties();
            policyProperties.loadFromXML(is);

            return ConfigurationTool.bindProperties(MuProcessManagementPolicy.class, policyProperties);
        }
        catch (IOException ioe) {
            String info = "Failed to load process management policy from \"" + file.getAbsolutePath() + "\": ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static MuProcessManagementPolicy getManagementPolicy(Class clazz, String resource) throws MuProcessException {
        Objects.requireNonNull(clazz, "clazz");

        try (InputStream is = clazz.getResourceAsStream(resource)) {
            if (null == is) {
                String info = "Unknown resource: class=\"" + clazz.getName() + "\", resource=\"" + resource + "\"";
                throw new IllegalArgumentException(info);
            }
            final Properties policyProperties = new Properties();
            policyProperties.loadFromXML(is);

            return ConfigurationTool.bindProperties(MuProcessManagementPolicy.class, policyProperties);
        }
        catch (IOException ioe) {
            String info = "Failed to load process management policy: ";
            info += ioe.getMessage();
            throw new MuProcessException(info, ioe);
        }
    }

    public static MuProcessManagementPolicy getDefaultManagementPolicy() throws MuProcessException {
        return getManagementPolicy(MuSynchronousManagerImpl.class, "default-management-policy.xml");
    }
}
