package rundeck.services.scm

import com.dtolabs.rundeck.app.support.ScheduledExecutionQuery
import com.dtolabs.rundeck.plugins.scm.JobExportReference
import com.dtolabs.rundeck.plugins.scm.JobScmReference
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext
import grails.events.annotation.Subscriber
import grails.gorm.transactions.Transactional
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import rundeck.ScheduledExecution
import rundeck.services.ConfigurationService
import rundeck.services.FrameworkService
import rundeck.services.ScheduledExecutionService
import rundeck.services.ScmService

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@CompileStatic
class ScmLoaderService {

    FrameworkService frameworkService
    ScmService scmService
    ScheduledExecutionService scheduledExecutionService
    ConfigurationService configurationService
    public static final long DEFAULT_LOADER_DELAY = 0
    public static final long DEFAULT_LOADER_INTERVAL_SEC = 30

    /**
     * scheduledExecutor to load job SCM cache
     */
    ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2)
    final Map<String, ScheduledFuture> scmProjectLoaderProcess = Collections.synchronizedMap([:])
    final Map<String, Boolean> scmProjectInitLoaded = Collections.synchronizedMap([:])

    @Subscriber("rundeck.bootstrap")
    @CompileDynamic
    void beginScmLoader(){
        if(frameworkService) {

            //check if each project has set the SCM Loader process (if needed)
            scheduledExecutor.scheduleAtFixedRate(
                    {
                        for (String project : frameworkService.projectNames()) {
                            for (String integration : scmService.INTEGRATIONS) {
                                String projectIntegration = project + "-" + integration
                                ScmPluginConfigData pluginConfigData = scmService.loadScmConfig(project, integration)
                                if(!scmProjectLoaderProcess.get(projectIntegration)){
                                    if(pluginConfigData && pluginConfigData.enabled) {
                                        scmProjectLoaderProcess.put(projectIntegration, startScmLoader(project, integration))
                                    }
                                }else{
                                    //cleanup: if scm was disabled or the project was deleted
                                    if(pluginConfigData && !pluginConfigData.enabled || !pluginConfigData) {
                                        ScheduledFuture scheduler = scmProjectLoaderProcess.get(projectIntegration)
                                        scheduler.cancel(true)
                                        scmProjectLoaderProcess.remove(projectIntegration)
                                        cleanUpScmPlugin(project, integration)
                                    }
                                }
                            }
                        }
                    },
                    scmLoaderInitialDelaySeconds,
                    scmLoaderIntervalSeconds,
                    TimeUnit.SECONDS
            )
        }
    }

    def startScmLoader(String project, String integration){

        //enable project integration cache loader
        def scheduler = scheduledExecutor.scheduleAtFixedRate(
                {
                    String projectIntegration = project + "-" + integration
                    ScmPluginConfigData pluginConfigData = scmService.loadScmConfig(project, integration)
                    if(pluginConfigData && pluginConfigData.enabled){
                        try {
                            if (integration == scmService.EXPORT) {
                                processScmExportLoader(project, pluginConfigData)
                            }else{
                                processScmImportLoader(project, pluginConfigData)
                            }

                        } catch (Throwable t) {
                            log.error("processMessages error: $project/$integration: ${t.message}")
                        }
                    }else{
                        //removing task
                        log.debug("removing thread ${projectIntegration}")
                        scmProjectLoaderProcess.remove(projectIntegration)
                        cleanUpScmPlugin(project, integration)
                        throw new RuntimeException("SCM disabled or project removed");
                    }
                },
                scmLoaderInitialDelaySeconds,
                scmLoaderIntervalSeconds,
                TimeUnit.SECONDS
        )
        scheduler
    }

    long getScmLoaderInitialDelaySeconds() {
        configurationService?.getLong('scm.loader.delay', DEFAULT_LOADER_DELAY) ?: DEFAULT_LOADER_DELAY
    }

    long getScmLoaderIntervalSeconds() {
        configurationService?.getLong(
                'scm.loader.delay',
                DEFAULT_LOADER_INTERVAL_SEC
        ) ?:
                DEFAULT_LOADER_INTERVAL_SEC
    }

    @CompileDynamic
    List<ScheduledExecution> getJobs(String project){
        def query=new ScheduledExecutionQuery()
        query.projFilter = project
        def listWorkflows = scheduledExecutionService.listWorkflows(query)
        List<ScheduledExecution> jobs = listWorkflows["schedlist"]
        return jobs
    }

    @Transactional
    def processScmExportLoader(String project, ScmPluginConfigData pluginConfig){

        if (!scmService.projectHasConfiguredExportPlugin(project)) {
            return
        }

        log.debug("processing SCM export Loader ${project} / ${pluginConfig.type}")

        def plugin = scmService.getLoadedExportPluginFor(project)

        if(plugin){
            log.debug("export plugin found")

            List<ScheduledExecution> jobs = getJobs(project)
            log.debug("processing ${jobs.size()} jobs")

            List<JobExportReference> joblist = scmService.exportjobRefsForJobs(jobs)

            def key = project+"-export"
            if(!scmProjectInitLoaded.containsKey(key)){
                plugin.initJobsStatus(joblist)
                //loading first time job status
                log.debug("refresh jobs status")
                plugin.refreshJobsStatus(joblist)
                scmProjectInitLoaded.put(key, true)
            }

            log.debug("processing cluster fix")
            if(frameworkService.isClusterModeEnabled()){
                def username = pluginConfig.getSetting("username")
                def roles = pluginConfig.getSettingList("roles")
                ScmOperationContext context = scmService.scmOperationContext(username, roles, project)
                Map<String,String> originalPaths = joblist.collectEntries{[it.id,scmService.getRenamedPathForJobId(it.project, it.id)]}

                //run cluster fix
                plugin.clusterFixJobs(context, joblist, originalPaths)
            }
        }

    }

    @Transactional
    def processScmImportLoader(String project, ScmPluginConfigData pluginConfig){
        log.debug("processing SCM import Loader ${project} / ${pluginConfig.type}")

        if (!scmService.projectHasConfiguredImportPlugin(project)) {
            return
        }

        def plugin = scmService.getLoadedImportPluginFor(project)

        if(plugin){
            log.debug("import plugin found")

            List<ScheduledExecution> jobs = getJobs(project)
            log.debug("processing ${jobs.size()} jobs")

            List<JobScmReference> joblist = scmService.scmJobRefsForJobs(jobs)

            def key = project+"-import"
            if(!scmProjectInitLoaded.containsKey(key)){
                plugin.initJobsStatus(joblist)
                //loading first time job status
                log.debug("refresh jobs status")
                //loading first time job status
                plugin.refreshJobsStatus(joblist)

                scmProjectInitLoaded.put(key, true)
            }

            if(frameworkService.isClusterModeEnabled()){
                def username = pluginConfig.getSetting("username")
                def roles = pluginConfig.getSettingList("roles")

                def context = scmService.scmOperationContext(username, roles, project)
                Map<String,String> originalPaths = joblist.collectEntries { [it.id, scmService.getRenamedPathForJobId(it.project, it.id)] }

                //run cluster fix
                plugin.clusterFixJobs(context, joblist, originalPaths)
            }


        }
    }


    def cleanUpScmPlugin(String project, String integration){
        def key = project + "-" + integration
        if(scmProjectInitLoaded.containsKey(key)){
            scmProjectInitLoaded.remove(key)
        }

        scmService.unregisterPlugin(integration, project)
    }

}