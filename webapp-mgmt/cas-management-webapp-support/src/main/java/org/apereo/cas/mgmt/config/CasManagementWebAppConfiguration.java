package org.apereo.cas.mgmt.config;

import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.mgmt.configuration.CasManagementConfigurationProperties;
import org.apereo.cas.configuration.model.support.oidc.OidcProperties;
import org.apereo.cas.configuration.support.Beans;
import org.apereo.cas.mgmt.CasManagementUtils;
import org.apereo.cas.mgmt.DefaultCasManagementEventListener;
import org.apereo.cas.mgmt.authentication.CasManagementSecurityInterceptor;
import org.apereo.cas.mgmt.authentication.CasUserProfileFactory;
import org.apereo.cas.mgmt.services.web.ForwardingController;
import org.apereo.cas.mgmt.services.web.ManageRegisteredServicesMultiActionController;
import org.apereo.cas.mgmt.services.web.RegisterController;
import org.apereo.cas.mgmt.services.web.RegisteredServiceSimpleFormController;
import org.apereo.cas.mgmt.services.web.ServiceRepsositoryController;
import org.apereo.cas.mgmt.services.web.SubmissionController;
import org.apereo.cas.mgmt.services.web.factory.ManagerFactory;
import org.apereo.cas.mgmt.services.web.factory.RepositoryFactory;
import org.apereo.cas.mgmt.web.CasManagementRootController;
import org.apereo.cas.oidc.claims.BaseOidcScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcCustomScopeAttributeReleasePolicy;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.io.CommunicationsManager;
import org.apereo.services.persondir.IPersonAttributeDao;
import org.pac4j.core.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter;
import org.springframework.web.servlet.mvc.UrlFilenameViewController;
import org.thymeleaf.spring4.templateresolver.SpringResourceTemplateResolver;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * This is {@link CasManagementWebAppConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("casManagementWebAppConfiguration")
@EnableConfigurationProperties(CasManagementConfigurationProperties.class)
public class CasManagementWebAppConfiguration extends WebMvcConfigurerAdapter {

    @Autowired
    private ServerProperties serverProperties;


    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("casManagementSecurityConfiguration")
    private Config casManagementSecurityConfiguration;

    @Autowired
    private CasManagementConfigurationProperties casProperties;

    @Autowired
    @Qualifier("webApplicationServiceFactory")
    private ServiceFactory<WebApplicationService> webApplicationServiceFactory;

    @Autowired
    @Qualifier("casUserProfileFactory")
    private CasUserProfileFactory casUserProfileFactory;

    @Autowired
    @Qualifier("servicesManager")
    private ServicesManager servicesManager;

    @Bean
    public Filter characterEncodingFilter() {
        return new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true);
    }

    @RefreshScope
    @ConditionalOnMissingBean(name = "attributeRepository")
    @Bean
    public IPersonAttributeDao attributeRepository() {
        return Beans.newStubAttributeRepository(casProperties.getAuthn().getAttributeRepository());
    }

    @Bean
    public Controller rootController() {
        return new CasManagementRootController();
    }

    @Bean
    public SimpleUrlHandlerMapping handlerMappingC() {
        final SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);
        mapping.setAlwaysUseFullPath(true);
        mapping.setRootHandler(rootController());

        final Properties properties = new Properties();
        properties.put("/*.html", new UrlFilenameViewController());
        mapping.setMappings(properties);
        return mapping;
    }

    @Bean
    public HandlerInterceptorAdapter casManagementSecurityInterceptor() {
        return new CasManagementSecurityInterceptor(casManagementSecurityConfiguration);
    }

    @ConditionalOnMissingBean(name = "localeResolver")
    @Bean
    public LocaleResolver localeResolver() {
        return new CookieLocaleResolver() {
            @Override
            protected Locale determineDefaultLocale(final HttpServletRequest request) {
                final Locale locale = request.getLocale();
                if (StringUtils.isEmpty(casProperties.getMgmt().getDefaultLocale())
                        || !locale.getLanguage().equals(casProperties.getMgmt().getDefaultLocale())) {
                    return locale;
                }
                return new Locale(casProperties.getMgmt().getDefaultLocale());
            }
        };
    }

    @RefreshScope
    @Bean
    public HandlerInterceptor localeChangeInterceptor() {
        final LocaleChangeInterceptor bean = new LocaleChangeInterceptor();
        bean.setParamName(this.casProperties.getLocale().getParamName());
        return bean;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(casManagementSecurityInterceptor())
                .addPathPatterns("/**").excludePathPatterns("/callback*", "/logout*", "/authorizationFailure");
    }

    @Bean
    public SimpleControllerHandlerAdapter simpleControllerHandlerAdapter() {
        return new SimpleControllerHandlerAdapter();
    }

    @Bean
    public ForwardingController forwardingController() {
        return new ForwardingController();
    }

    @Bean
    public ManageRegisteredServicesMultiActionController manageRegisteredServicesMultiActionController(
            @Qualifier("servicesManager") final ServicesManager servicesManager) {
        final String defaultCallbackUrl = CasManagementUtils.getDefaultCallbackUrl(casProperties, serverProperties);
        return new ManageRegisteredServicesMultiActionController(servicesManager, attributeRepository(),
                webApplicationServiceFactory, defaultCallbackUrl, casProperties, casUserProfileFactory, managerFactory(), repositoryFactory());
    }

    @Bean
    public RegisteredServiceSimpleFormController registeredServiceSimpleFormController(@Qualifier("servicesManager") final ServicesManager servicesManager) {
        return new RegisteredServiceSimpleFormController(servicesManager, managerFactory(), casUserProfileFactory, repositoryFactory());
    }

    @Bean
    public RepositoryFactory repositoryFactory() {
        return new RepositoryFactory(casProperties, casUserProfileFactory);
    }

    @Bean
    public ManagerFactory managerFactory() {
        return new ManagerFactory(servicesManager, casProperties, repositoryFactory(), casUserProfileFactory);
    }

    @Bean
    public RegisterController registerController() {
        return new RegisterController();
    }

    @Bean
    public SubmissionController submissionController() {
        return new SubmissionController();
    }

    @Bean
    public ServiceRepsositoryController serviceRepsositoryController() {
        return new ServiceRepsositoryController(repositoryFactory(), managerFactory(), casUserProfileFactory,
                casProperties, servicesManager, communicationsManager());
    }

    @RefreshScope
    @Bean
    public Collection<BaseOidcScopeAttributeReleasePolicy> userDefinedScopeBasedAttributeReleasePolicies() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        return oidc.getUserDefinedScopes().entrySet()
                .stream()
                .map(k -> new OidcCustomScopeAttributeReleasePolicy(k.getKey(), CollectionUtils.wrapList(k.getValue().split(","))))
                .collect(Collectors.toSet());
    }

    @Bean
    public DefaultCasManagementEventListener defaultCasManagementEventListener() {
        return new DefaultCasManagementEventListener();
    }

    @Bean
    public SpringResourceTemplateResolver manageStaticTemplateResolver() {
        final SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(this.context);
        resolver.setPrefix("classpath:/dist/manage/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(Charset.forName("UTF-8").name());
        resolver.setCacheable(false);
        resolver.setOrder(0);
        resolver.setCheckExistence(true);
        return resolver;
    }

    @Bean SpringResourceTemplateResolver registerStaticTemplateResolver() {
        final SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(this.context);
        resolver.setPrefix("classpath:/dist/register/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(Charset.forName("UTF-8").name());
        resolver.setCacheable(false);
        resolver.setOrder(1);
        resolver.setCheckExistence(true);
        return resolver;
    }

    @Bean
    public CommunicationsManager communicationsManager() {
        return new CommunicationsManager();
    }

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/dist/register/", "classpath:/dist/manage/", "classpath:/static/");
    }
}
