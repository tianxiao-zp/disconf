package com.baidu.disconf.client.addons.properties;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.PropertiesFactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.baidu.disconf.client.DisconfMgr;
import com.baidu.disconf.client.config.DisClientConfig;

/**
 * A properties factory bean that creates a reconfigurable Properties object.
 * When the Properties' reloadConfiguration method is called, and the file has
 * changed, the properties are read again from the file.
 * <p/>
 * 真正的 reload bean 定义，它可以定义多个 resource 为 reload config file
 */
public class ReloadablePropertiesFactoryBean extends PropertiesFactoryBean implements DisposableBean,
        ApplicationContextAware {

	private ApplicationContext applicationContext;
    protected static final Logger log = LoggerFactory.getLogger(ReloadablePropertiesFactoryBean.class);

    private Resource[] locations;
    private long[] lastModified;
    private List<IReloadablePropertiesListener> preListeners;

    /**
     * 定义资源文件
     *
     * @param fileNames
     */
    public void setLocation(final String fileNames) {
        List<String> list = new ArrayList<String>();
        list.add(fileNames);
        setLocations(list);
    }
    
    /**
     * @author Zhu
     * @date 2017年2月21日 下午8:59:11
     * @description 
     * @param fileNames
     * @param configArrStr
     */
    private void addFile(List<String> fileNames, String configArrStr){
    	if (null != configArrStr && !"".equals(configArrStr.trim())) {
			String[] split = configArrStr.split(",");
			if (ArrayUtils.isNotEmpty(split)) {
				fileNames.addAll(0, Arrays.asList(split));
			}
		}
    }
    

	/**
	 * 加入common app disconf配置文件中扩展的配置文件名称，可将托管的配置文件配在disconf配置文件里
	 * 
	 * @param fileNames
	 */
	private void addExtFilesName(List<String> fileNames) {
		addFile(fileNames, DisClientConfig.getInstance().COMMON_APP_CONF_FILES_NAME);
	}
	
	/**
	 * 加入app disconf配置文件中扩展的配置文件名称，可将托管的配置文件配在disconf配置文件里
	 * 
	 * @param fileNames
	 */
	private void addSpecificFilesName(List<String> fileNames) {
		addFile(fileNames, DisClientConfig.getInstance().APP_CONF_FILES_NAME);
	}

    /**
     */
    public void setLocations(List<String> fileNames) {

        List<Resource> resources = new ArrayList<Resource>();
        addSpecificFilesName(fileNames);
        addExtFilesName(fileNames);
        for (String filename : fileNames) {

            // trim
            filename = filename.trim();

            String realFileName = getFileName(filename);

            //
            // register to disconf
            //
            DisconfMgr.getInstance().reloadableScan(realFileName);

            //
            // only properties will reload
            //
            String ext = FilenameUtils.getExtension(filename);
            if (ext.equals("properties")) {

                PathMatchingResourcePatternResolver pathMatchingResourcePatternResolver =
                        new PathMatchingResourcePatternResolver();
                try {
                    Resource[] resourceList = pathMatchingResourcePatternResolver.getResources(filename);
                    for (Resource resource : resourceList) {
                        resources.add(resource);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        this.locations = resources.toArray(new Resource[resources.size()]);
        lastModified = new long[locations.length];
        super.setLocations(locations);
    }

    /**
     * get file name from resource
     *
     * @param fileName
     *
     * @return
     */
    private String getFileName(String fileName) {

        if (fileName != null) {
            int index = fileName.indexOf(':');
            if (index < 0) {
                return fileName;
            } else {

                fileName = fileName.substring(index + 1);

                index = fileName.lastIndexOf('/');
                if (index < 0) {
                    return fileName;
                } else {
                    return fileName.substring(index + 1);
                }

            }
        }
        return null;
    }

    protected Resource[] getLocations() {
        return locations;
    }

    /**
     * listener , 用于通知回调
     *
     * @param listeners
     */
    public void setListeners(final List listeners) {
        // early type check, and avoid aliassing
        this.preListeners = new ArrayList<IReloadablePropertiesListener>();
        for (Object o : listeners) {
            preListeners.add((IReloadablePropertiesListener) o);
        }
    }

    private ReloadablePropertiesBase reloadableProperties;

    /**
     * @return
     *
     * @throws IOException
     */
    @Override
    protected Properties createProperties() throws IOException {

        return (Properties) createMyInstance();
    }

    /**
     * createInstance 废弃了
     *
     * @throws IOException
     */
    protected Object createMyInstance() throws IOException {
        // would like to uninherit from AbstractFactoryBean (but it's final!)
        if (!isSingleton()) {
            throw new RuntimeException("ReloadablePropertiesFactoryBean only works as singleton");
        }

        // set listener
        reloadableProperties = new ReloadablePropertiesImpl();
        if (preListeners != null) {
            reloadableProperties.setListeners(preListeners);
        }

        // reload
        reload(true);

        // add for monitor
        ReloadConfigurationMonitor.addReconfigurableBean((ReconfigurableBean) reloadableProperties);

        return reloadableProperties;
    }

    public void destroy() throws Exception {
        reloadableProperties = null;
    }

    /**
     * 根据修改时间来判定是否reload
     *
     * @param forceReload
     *
     * @throws IOException
     */
    protected void reload(final boolean forceReload) throws IOException {

        boolean reload = forceReload;
        for (int i = 0; i < locations.length; i++) {
            Resource location = locations[i];
            File file;

            try {
                file = location.getFile();
            } catch (IOException e) {
                // not a file resource
                // may be spring boot
                log.warn(e.toString());
                continue;
            }
            try {
                long l = file.lastModified();

                if (l > lastModified[i]) {
                    lastModified[i] = l;
                    reload = true;
                }
            } catch (Exception e) {
                // cannot access file. assume unchanged.
                if (log.isDebugEnabled()) {
                    log.debug("can't determine modification time of " + file + " for " + location, e);
                }
            }
        }
        if (reload) {
            doReload();
        }
    }

    /**
     * 设置新的值
     *
     * @throws IOException
     */
    private void doReload() throws IOException {
        Properties properties = mergeProperties();
		reloadableProperties.setProperties(properties);
        injectToEnv(properties);
    }

    /**
     * @return
     */
    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    	this.applicationContext = applicationContext;
    }
    
    private void injectToEnv(Properties properties) throws IOException{
		Environment environment = applicationContext.getEnvironment();
		ConfigurableEnvironment env = (ConfigurableEnvironment) environment;
		env.getPropertySources().addFirst(new PropertiesPropertySource("application", properties));
    }

    /**
     * 回调自己
     */
    class ReloadablePropertiesImpl extends ReloadablePropertiesBase implements ReconfigurableBean {

        // reload myself
        public void reloadConfiguration() throws Exception {
            ReloadablePropertiesFactoryBean.this.reload(false);
        }
    }

}
