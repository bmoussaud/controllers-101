package io.spring;

import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.util.Yaml;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.spring.models.V1Foo;
import io.spring.models.V1FooList;
import lombok.SneakyThrows;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class FooControllerApplication {

    private static final Log log = LogFactory.getLog(FooControllerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FooControllerApplication.class, args);
    }

    @Bean
    GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentsApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Deployment.class, V1DeploymentList.class,
                "", "v1", "deployments",
                apiClient);
    }

    @Bean
    GenericKubernetesApi<V1Foo, V1FooList> foosApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1Foo.class, V1FooList.class, "spring.io", "v1",
                "foos", apiClient);
    }

    @Bean
    SharedIndexInformer<V1Foo> fooNodeInformer(
            SharedInformerFactory sharedInformerFactory,
            GenericKubernetesApi<V1Foo, V1FooList> configClientApi) {
        return sharedInformerFactory.sharedIndexInformerFor(configClientApi, V1Foo.class, 0);
    }

    @SneakyThrows
    private static <T> T loadYamlAs(Resource resource, Class<T> clzz) {
        var yaml = FileCopyUtils.copyToString(
                new InputStreamReader(resource.getInputStream()));
        return Yaml.loadAs(yaml, clzz);
    }

    static class FooReconciler implements Reconciler {

        private final AppsV1Api coreV1Api;
        private final Resource resourceForDeploymentYaml;
        private final SharedIndexInformer<V1Foo> fooNodeInformer;
        private final GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi;

        FooReconciler(AppsV1Api coreV1Api, Resource resourceForDeploymentYaml, SharedIndexInformer<V1Foo> fooNodeInformer, GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi) {
            this.coreV1Api = coreV1Api;
            this.resourceForDeploymentYaml = resourceForDeploymentYaml;
            this.fooNodeInformer = fooNodeInformer;
            this.deploymentApi = deploymentApi;
        }

        @Override
        @SneakyThrows
        public Result reconcile(Request request) {

            var foo = fooNodeInformer.getIndexer().getByKey(request.getNamespace() + "/" + request.getName());
            if (foo != null) {
                log.info("there's a new Foo in town! Let's make sure we've got a deployment to match...");
                var nameOfDeployment = foo.getMetadata().getName() + "-deployment";
                log.info("does the deployment called " + nameOfDeployment + " exist?");
                var existingDeployment = this.deploymentApi.get(foo.getMetadata().getNamespace(), nameOfDeployment);
                if (existingDeployment != null && existingDeployment.isSuccess()) {
                     log.info("the deployment already exists!");
                } //
                else {
                    var deployment = loadYamlAs(resourceForDeploymentYaml, V1Deployment.class);
                    deployment .getMetadata().setName(  nameOfDeployment);

                    var namespacedDeployment = this.coreV1Api.createNamespacedDeployment(
                            foo.getMetadata().getNamespace(), deployment, "true", null, "", "");
                    Assert.notNull(namespacedDeployment, () -> "the Deployment result should be non-null");
                    log.info("created a deployment called "+ nameOfDeployment);
                }
            }//
            else {
                log.info("there are no Foos. This should be queued up for deletion, perhaps?");
            }
            return new Result(false);
        }
    }

    @Bean
    AppsV1Api appsV1Api(ApiClient apiClient) {
        return new AppsV1Api(apiClient);
    }

    @Bean
    Reconciler reconciler(AppsV1Api coreV1Api,
                          @Value("classpath:/deployment.yaml") Resource resourceForDeploymentYaml,
                          SharedIndexInformer<V1Foo> fooNodeInformer,
                          GenericKubernetesApi<V1Deployment, V1DeploymentList> deploymentApi) {
        return new FooReconciler(coreV1Api, resourceForDeploymentYaml, fooNodeInformer, deploymentApi);
    }

    @Bean
    Controller controller(SharedInformerFactory sharedInformerFactory,
                          SharedIndexInformer<V1Foo> fooNodeInformer,
                          Reconciler reconciler) {
        var builder = ControllerBuilder //
                .defaultBuilder(sharedInformerFactory)//
                .watch((q) -> ControllerBuilder //
                        .controllerWatchBuilder(V1Foo.class, q)
                        .withResyncPeriod(Duration.ofHours(1)).build() //
                ) //
                .withWorkerCount(2);
        return builder
                .withReconciler(reconciler) //
                .withReadyFunc(fooNodeInformer::hasSynced) // optional: only start once the index is synced
                .withName("fooController") ///
                .build();

    }


    @Bean
    ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    ApplicationRunner runner(ExecutorService executorService,
                             SharedInformerFactory sharedInformerFactory,
                             Controller controller) {
        return args -> executorService.execute(() -> {
            sharedInformerFactory.startAllRegisteredInformers();
            controller.run();
        });
    }

}

