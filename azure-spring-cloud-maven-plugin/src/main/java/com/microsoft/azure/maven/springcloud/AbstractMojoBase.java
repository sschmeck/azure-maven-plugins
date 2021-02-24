/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.maven.springcloud;

import com.azure.core.management.AzureEnvironment;
import com.microsoft.azure.common.exceptions.AzureExecutionException;
import com.microsoft.azure.common.logging.Log;
import com.microsoft.azure.management.appplatform.v2020_07_01.implementation.AppPlatformManager;
import com.microsoft.azure.maven.auth.MavenAuthManager;
import com.microsoft.azure.maven.exception.MavenDecryptException;
import com.microsoft.azure.maven.model.MavenAuthConfiguration;
import com.microsoft.azure.maven.springcloud.config.AppDeploymentMavenConfig;
import com.microsoft.azure.maven.springcloud.config.ConfigurationParser;
import com.microsoft.azure.maven.telemetry.AppInsightHelper;
import com.microsoft.azure.maven.telemetry.MojoStatus;
import com.microsoft.azure.maven.utils.ProxyUtils;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.AzureAccount;
import com.microsoft.azure.toolkit.lib.auth.exception.LoginFailureException;
import com.microsoft.azure.toolkit.lib.auth.model.SubscriptionEntity;
import com.microsoft.azure.toolkit.lib.auth.util.AzureEnvironmentUtils;
import com.microsoft.azure.toolkit.lib.common.utils.TextUtils;
import com.microsoft.azure.toolkit.lib.springcloud.config.SpringCloudAppConfig;
import com.microsoft.azure.tools.exception.InvalidConfigurationException;
import com.microsoft.rest.LogLevel;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.SettingsDecrypter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_AUTH_METHOD;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_CPU;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_DURATION;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_ERROR_CODE;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_ERROR_MESSAGE;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_ERROR_TYPE;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_INSTANCE_COUNT;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_IS_KEY_ENCRYPTED;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_IS_SERVICE_PRINCIPAL;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_JAVA_VERSION;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_JVM_OPTIONS;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_MEMORY;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_PLUGIN_NAME;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_PLUGIN_VERSION;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_PUBLIC;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_RUNTIME_VERSION;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_SUBSCRIPTION_ID;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_KEY_WITHIN_PARENT_POM;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_VALUE_AUTH_POM_CONFIGURATION;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_VALUE_ERROR_CODE_FAILURE;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_VALUE_ERROR_CODE_SUCCESS;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_VALUE_SYSTEM_ERROR;
import static com.microsoft.azure.maven.springcloud.TelemetryConstants.TELEMETRY_VALUE_USER_ERROR;

public abstract class AbstractMojoBase extends AbstractMojo {
    private static final String INIT_FAILURE = "InitFailure";
    private static final String AZURE_INIT_FAIL = "Failed to authenticate with Azure. Please check your configuration.";
    private static final String USING_AZURE_ENVIRONMENT = "Using Azure environment: %s.";

    @Parameter(property = "auth")
    protected MavenAuthConfiguration auth;

    @Parameter(alias = "public")
    protected Boolean isPublic;

    @Parameter(property = "isTelemetryAllowed", defaultValue = "true")
    protected boolean isTelemetryAllowed;

    @Parameter(property = "subscriptionId")
    protected String subscriptionId;

    @Parameter(property = "clusterName")
    protected String clusterName;

    @Parameter(property = "appName")
    protected String appName;

    @Parameter(property = "runtimeVersion")
    protected String runtimeVersion;

    @Parameter(property = "deployment")
    protected AppDeploymentMavenConfig deployment;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File buildDirectory;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    protected PluginDescriptor plugin;

    protected Map<String, String> telemetries;

    @Component
    protected SettingsDecrypter settingsDecrypter;

    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    protected Long timeStart;
    private AppPlatformManager manager;

    @Parameter(property = "authType")
    protected String authType;

    /**
     * Use a HTTP proxy host for the Azure Auth Client
     */
    @Parameter(property = "httpProxyHost")
    protected String httpProxyHost;

    /**
     * Use a HTTP proxy port for the Azure Auth Client
     */
    @Parameter(property = "httpProxyPort")
    protected String httpProxyPort;

    @Override
    public void execute() throws MojoFailureException {
        try {
            initExecution();
            doExecute();
            handleSuccess();
        } catch (Exception e) {
            handleException(e);
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    protected void initExecution()
            throws MojoFailureException, MavenDecryptException, AzureExecutionException, LoginFailureException {
        // init proxy manager
        ProxyUtils.initProxy(Optional.ofNullable(this.session).map(s -> s.getRequest()).orElse(null));
        // Init telemetries
        initTelemetry();
        Azure.az().config().setLogLevel(LogLevel.NONE);
        Azure.az().config().setUserAgent(getUserAgent());
        trackMojoExecution(MojoStatus.Start);
        final MavenAuthConfiguration mavenAuthConfiguration = auth == null ? new MavenAuthConfiguration() : auth;
        mavenAuthConfiguration.setType(getAuthType());
        MavenAuthManager.getInstance().login(MavenAuthManager.getInstance().buildAuthConfiguration(session, settingsDecrypter, mavenAuthConfiguration));
        Account account = Azure.az(AzureAccount.class).account();
        if (!account.isAuthenticated()) {
            AppInsightHelper.INSTANCE.trackEvent(INIT_FAILURE);
            throw new MojoFailureException(AZURE_INIT_FAIL);
        }
        final AzureEnvironment env = account.getEnvironment();
        final String environmentName = AzureEnvironmentUtils.azureEnvironmentToString(env);
        if (env != AzureEnvironment.AZURE) {
            Log.prompt(String.format(USING_AZURE_ENVIRONMENT, TextUtils.cyan(environmentName)));
        }
        printCredentialDescription(account);
    }

    protected String getAuthType() {
        return StringUtils.firstNonBlank(auth == null ? null : auth.getType(), authType);
    }

    protected void initTelemetry() {
        timeStart = System.currentTimeMillis();
        telemetries = new HashMap<>();
        if (!isTelemetryAllowed) {
            AppInsightHelper.INSTANCE.disable();
        }
        tracePluginInformation();
    }

    protected void printCredentialDescription(Account account) {
        List<String> details = new ArrayList<>();
        details.add(String.format("Auth method: %s", TextUtils.cyan(account.getEntity().getMethod().toString())));
        List<SubscriptionEntity> selectedSubscriptions = account.getSelectedSubscriptions();
        if (StringUtils.isNotEmpty(account.getEntity().getEmail())) {
            details.add(String.format("Username: %s", TextUtils.cyan(account.getEntity().getEmail())));
        }
        if (selectedSubscriptions != null && selectedSubscriptions.size() == 1) {
            details.add(String.format("Default subscription: %s", TextUtils.cyan(selectedSubscriptions.get(0).getId())));
        }
        System.out.println(StringUtils.join(details.toArray(), "\n"));
    }

    protected void handleSuccess() {
        telemetries.put(TELEMETRY_KEY_ERROR_CODE, TELEMETRY_VALUE_ERROR_CODE_SUCCESS);
        telemetries.put(TELEMETRY_KEY_DURATION, String.valueOf(System.currentTimeMillis() - timeStart));
        trackMojoExecution(MojoStatus.Success);
    }

    protected void handleException(Exception exception) {
        final boolean isUserError = exception instanceof IllegalArgumentException ||
                exception instanceof InvalidConfigurationException;
        telemetries.put(TELEMETRY_KEY_ERROR_CODE, TELEMETRY_VALUE_ERROR_CODE_FAILURE);
        telemetries.put(TELEMETRY_KEY_ERROR_TYPE, isUserError ? TELEMETRY_VALUE_USER_ERROR : TELEMETRY_VALUE_SYSTEM_ERROR);
        telemetries.put(TELEMETRY_KEY_ERROR_MESSAGE, exception.getMessage());
        telemetries.put(TELEMETRY_KEY_DURATION, String.valueOf(System.currentTimeMillis() - timeStart));
        trackMojoExecution(MojoStatus.Failure);
    }

    protected void trackMojoExecution(MojoStatus status) {
        final String eventName = String.format("%s.%s", this.getClass().getSimpleName(), status.name());
        AppInsightHelper.INSTANCE.trackEvent(eventName, getTelemetryProperties(), false);
    }

    protected void tracePluginInformation() {
        final String javaVersion = String.format("%s %s", System.getProperty("java.vendor"), System.getProperty("java.version"));
        telemetries.put(TELEMETRY_KEY_PLUGIN_NAME, plugin.getArtifactId());
        telemetries.put(TELEMETRY_KEY_PLUGIN_VERSION, plugin.getVersion());
        telemetries.put(TELEMETRY_KEY_WITHIN_PARENT_POM, String.valueOf(project.getPackaging().equalsIgnoreCase("pom")));
        telemetries.put(TELEMETRY_KEY_JAVA_VERSION, javaVersion);
    }

    protected void traceConfiguration(SpringCloudAppConfig configuration) {
        telemetries.put(TELEMETRY_KEY_PUBLIC, String.valueOf(configuration.isPublic()));
        telemetries.put(TELEMETRY_KEY_RUNTIME_VERSION, configuration.getRuntimeVersion());
        telemetries.put(TELEMETRY_KEY_CPU, String.valueOf(configuration.getDeployment().getCpu()));
        telemetries.put(TELEMETRY_KEY_MEMORY, String.valueOf(configuration.getDeployment().getMemoryInGB()));
        telemetries.put(TELEMETRY_KEY_INSTANCE_COUNT, String.valueOf(configuration.getDeployment().getInstanceCount()));
        telemetries.put(TELEMETRY_KEY_JVM_OPTIONS,
                String.valueOf(StringUtils.isEmpty(configuration.getDeployment().getJvmOptions())));
        telemetries.put(TELEMETRY_KEY_SUBSCRIPTION_ID, configuration.getSubscriptionId());
    }

    protected void traceAuth() {
        // Todo update deploy mojo telemetries with real value
        telemetries.put(TELEMETRY_KEY_AUTH_METHOD, TELEMETRY_VALUE_AUTH_POM_CONFIGURATION);
        telemetries.put(TELEMETRY_KEY_IS_SERVICE_PRINCIPAL, "false");
        telemetries.put(TELEMETRY_KEY_IS_KEY_ENCRYPTED, "false");
    }

    protected abstract void doExecute() throws MojoExecutionException, MojoFailureException, AzureExecutionException;

    public boolean isPublic() {
        return isPublic;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getAppName() {
        return appName;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public AppDeploymentMavenConfig getDeployment() {
        return deployment;
    }

    public MavenProject getProject() {
        return project;
    }

    public MavenSession getSession() {
        return session;
    }

    public File getBuildDirectory() {
        return buildDirectory;
    }

    public PluginDescriptor getPlugin() {
        return plugin;
    }

    public SpringCloudAppConfig getConfiguration() {
        final ConfigurationParser parser = ConfigurationParser.getInstance();
        return parser.parse(this);
    }

    public Map<String, String> getTelemetryProperties() {
        return telemetries;
    }

    private String getUserAgent() {
        return isTelemetryAllowed ? String.format("%s/%s installationId:%s sessionId:%s", plugin.getArtifactId(), plugin.getVersion(),
                AppInsightHelper.INSTANCE.getInstallationId(), AppInsightHelper.INSTANCE.getSessionId())
                : String.format("%s/%s", plugin.getArtifactId(), plugin.getVersion());
    }
}
