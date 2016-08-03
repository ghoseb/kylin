/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.engine.mr.common;

/**
 * @author George Song (ysong1)
 *
 */

import static org.apache.hadoop.util.StringUtils.formatTime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.util.ClassUtil;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.RawResource;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.OptionsHelper;
import org.apache.kylin.common.util.StringSplitter;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.engine.mr.HadoopUtil;
import org.apache.kylin.invertedindex.IIInstance;
import org.apache.kylin.job.JobInstance;
import org.apache.kylin.job.exception.JobException;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.TableDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("static-access")
public abstract class AbstractHadoopJob extends Configured implements Tool {
    protected static final Logger logger = LoggerFactory.getLogger(AbstractHadoopJob.class);

    protected static final Option OPTION_JOB_NAME = OptionBuilder.withArgName(BatchConstants.ARG_JOB_NAME).hasArg().isRequired(true).withDescription("Job name. For example, Kylin_Cuboid_Builder-clsfd_v2_Step_22-D)").create(BatchConstants.ARG_JOB_NAME);
    protected static final Option OPTION_CUBE_NAME = OptionBuilder.withArgName(BatchConstants.ARG_CUBE_NAME).hasArg().isRequired(true).withDescription("Cube name. For exmaple, flat_item_cube").create(BatchConstants.ARG_CUBE_NAME);
    protected static final Option OPTION_CUBING_JOB_ID = OptionBuilder.withArgName(BatchConstants.ARG_CUBING_JOB_ID).hasArg().isRequired(false).withDescription("ID of cubing job executable").create(BatchConstants.ARG_CUBING_JOB_ID);
    protected static final Option OPTION_II_NAME = OptionBuilder.withArgName(BatchConstants.ARG_II_NAME).hasArg().isRequired(true).withDescription("II name. For exmaple, some_ii").create(BatchConstants.ARG_II_NAME);
    protected static final Option OPTION_SEGMENT_NAME = OptionBuilder.withArgName(BatchConstants.ARG_SEGMENT_NAME).hasArg().isRequired(true).withDescription("Cube segment name").create(BatchConstants.ARG_SEGMENT_NAME);
    protected static final Option OPTION_INPUT_PATH = OptionBuilder.withArgName(BatchConstants.ARG_INPUT).hasArg().isRequired(true).withDescription("Input path").create(BatchConstants.ARG_INPUT);
    protected static final Option OPTION_INPUT_FORMAT = OptionBuilder.withArgName(BatchConstants.ARG_INPUT_FORMAT).hasArg().isRequired(false).withDescription("Input format").create(BatchConstants.ARG_INPUT_FORMAT);
    protected static final Option OPTION_OUTPUT_PATH = OptionBuilder.withArgName(BatchConstants.ARG_OUTPUT).hasArg().isRequired(true).withDescription("Output path").create(BatchConstants.ARG_OUTPUT);
    protected static final Option OPTION_NCUBOID_LEVEL = OptionBuilder.withArgName(BatchConstants.ARG_LEVEL).hasArg().isRequired(true).withDescription("N-Cuboid build level, e.g. 1, 2, 3...").create(BatchConstants.ARG_LEVEL);
    protected static final Option OPTION_PARTITION_FILE_PATH = OptionBuilder.withArgName(BatchConstants.ARG_PARTITION).hasArg().isRequired(true).withDescription("Partition file path.").create(BatchConstants.ARG_PARTITION);
    protected static final Option OPTION_HTABLE_NAME = OptionBuilder.withArgName(BatchConstants.ARG_HTABLE_NAME).hasArg().isRequired(true).withDescription("HTable name").create(BatchConstants.ARG_HTABLE_NAME);

    protected static final Option OPTION_STATISTICS_ENABLED = OptionBuilder.withArgName(BatchConstants.ARG_STATS_ENABLED).hasArg().isRequired(false).withDescription("Statistics enabled").create(BatchConstants.ARG_STATS_ENABLED);
    protected static final Option OPTION_STATISTICS_OUTPUT = OptionBuilder.withArgName(BatchConstants.ARG_STATS_OUTPUT).hasArg().isRequired(false).withDescription("Statistics output").create(BatchConstants.ARG_STATS_OUTPUT);
    protected static final Option OPTION_STATISTICS_SAMPLING_PERCENT = OptionBuilder.withArgName(BatchConstants.ARG_STATS_SAMPLING_PERCENT).hasArg().isRequired(false).withDescription("Statistics sampling percentage").create(BatchConstants.ARG_STATS_SAMPLING_PERCENT);

    private static final String MAP_REDUCE_CLASSPATH = "mapreduce.application.classpath";

    private static final String KYLIN_HIVE_DEPENDENCY_JARS = "[^,]*hive-exec[0-9.-]+[^,]*?\\.jar" + "|" + "[^,]*hive-metastore[0-9.-]+[^,]*?\\.jar" + "|" + "[^,]*hive-hcatalog-core[0-9.-]+[^,]*?\\.jar";

    protected static void runJob(Tool job, String[] args) {
        try {
            int exitCode = ToolRunner.run(job, args);
            System.exit(exitCode);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(5);
        }
    }
    
    // ============================================================================

    protected String name;
    protected boolean isAsync = false;
    protected OptionsHelper optionsHelper = new OptionsHelper();

    protected Job job;

    public AbstractHadoopJob() {
        super(HadoopUtil.getCurrentConfiguration());
    }

    protected void parseOptions(Options options, String[] args) throws ParseException {
        optionsHelper.parseOptions(options, args);
    }

    public void printUsage(Options options) {
        optionsHelper.printUsage(getClass().getSimpleName(), options);
    }

    public Option[] getOptions() {
        return optionsHelper.getOptions();
    }

    public String getOptionsAsString() {
        return optionsHelper.getOptionsAsString();
    }

    protected String getOptionValue(Option option) {
        return optionsHelper.getOptionValue(option);
    }

    protected boolean hasOption(Option option) {
        return optionsHelper.hasOption(option);
    }

    protected int waitForCompletion(Job job) throws IOException, InterruptedException, ClassNotFoundException {
        int retVal = 0;
        long start = System.nanoTime();
        if (isAsync) {
            job.submit();
        } else {
            job.waitForCompletion(true);
            retVal = job.isSuccessful() ? 0 : 1;
            logger.debug("Job '" + job.getJobName() + "' finished " + (job.isSuccessful() ? "successfully in " : "with failures.  Time taken ") + formatTime((System.nanoTime() - start) / 1000000L));
        }
        return retVal;
    }

    protected void setJobClasspath(Job job, KylinConfig kylinConf) {
        String jarPath = kylinConf.getKylinJobJarPath();
        File jarFile = new File(jarPath);
        if (jarFile.exists()) {
            job.setJar(jarPath);
            logger.info("append job jar: " + jarPath);
        } else {
            job.setJarByClass(this.getClass());
        }

        String kylinHiveDependency = System.getProperty("kylin.hive.dependency");
        String kylinHBaseDependency = System.getProperty("kylin.hbase.dependency");
        logger.info("append kylin.hbase.dependency: " + kylinHBaseDependency + " to " + MAP_REDUCE_CLASSPATH);

        Configuration jobConf = job.getConfiguration();
        String classpath = jobConf.get(MAP_REDUCE_CLASSPATH);
        if (classpath == null || classpath.length() == 0) {
            logger.info("Didn't find " + MAP_REDUCE_CLASSPATH + " in job configuration, will run 'mapred classpath' to get the default value.");
            classpath = getDefaultMapRedClasspath();
            logger.info("The default mapred classpath is: " + classpath);
        }

        if (kylinHBaseDependency != null) {
            // yarn classpath is comma separated
            kylinHBaseDependency = kylinHBaseDependency.replace(":", ",");
            classpath = classpath + "," + kylinHBaseDependency;
        }

        jobConf.set(MAP_REDUCE_CLASSPATH, classpath);
        logger.info("Hadoop job classpath is: " + job.getConfiguration().get(MAP_REDUCE_CLASSPATH));

        /*
         *  set extra dependencies as tmpjars & tmpfiles if configured
         */
        StringBuilder kylinDependency = new StringBuilder();

        // for hive dependencies
        if (kylinHiveDependency != null) {
            // yarn classpath is comma separated
            kylinHiveDependency = kylinHiveDependency.replace(":", ",");

            logger.info("Hive Dependencies Before Filtered: " + kylinHiveDependency);
            String filteredHive = filterKylinHiveDependency(kylinHiveDependency);
            logger.info("Hive Dependencies After Filtered: " + filteredHive);

            if (kylinDependency.length() > 0)
                kylinDependency.append(",");
            kylinDependency.append(filteredHive);
        } else {

            logger.info("No hive dependency jars set in the environment, will find them from jvm:");

            try {
                String hiveExecJarPath = ClassUtil.findContainingJar(Class.forName("org.apache.hadoop.hive.ql.Driver"));
                kylinDependency.append(hiveExecJarPath).append(",");
                logger.info("hive-exec jar file: " + hiveExecJarPath);

                String hiveHCatJarPath = ClassUtil.findContainingJar(Class.forName("org.apache.hive.hcatalog.mapreduce.HCatInputFormat"));
                kylinDependency.append(hiveHCatJarPath).append(",");
                logger.info("hive-catalog jar file: " + hiveHCatJarPath);

                String hiveMetaStoreJarPath = ClassUtil.findContainingJar(Class.forName("org.apache.hadoop.hive.metastore.api.Table"));
                kylinDependency.append(hiveMetaStoreJarPath).append(",");
                logger.info("hive-metastore jar file: " + hiveMetaStoreJarPath);
            } catch (ClassNotFoundException e) {
                logger.error("Cannot found hive dependency jars: " + e);
            }
        }

        // for KylinJobMRLibDir
        String mrLibDir = kylinConf.getKylinJobMRLibDir();
        if (!StringUtils.isBlank(mrLibDir)) {
            File dirFileMRLIB = new File(mrLibDir);
            if (dirFileMRLIB.exists()) {
                if (kylinDependency.length() > 0)
                    kylinDependency.append(",");
                kylinDependency.append(mrLibDir);
            } else {
                logger.info("The directory '" + mrLibDir + "' for 'kylin.job.mr.lib.dir' does not exist!!!");
            }
        }

        setJobTmpJarsAndFiles(job, kylinDependency.toString());

        overrideJobConfig(job.getConfiguration(), kylinConf.getMRConfigOverride());
    }

    private void overrideJobConfig(Configuration jobConf, Map<String, String> override) {
        for (Entry<String, String> entry : override.entrySet()) {
            jobConf.set(entry.getKey(), entry.getValue());
        }
    }

    private String filterKylinHiveDependency(String kylinHiveDependency) {
        if (StringUtils.isBlank(kylinHiveDependency))
            return "";

        StringBuilder jarList = new StringBuilder();

        Pattern hivePattern = Pattern.compile(KYLIN_HIVE_DEPENDENCY_JARS);
        Matcher matcher = hivePattern.matcher(kylinHiveDependency);

        while (matcher.find()) {
            if (jarList.length() > 0)
                jarList.append(",");
            jarList.append(matcher.group());
        }

        return jarList.toString();
    }

    private void setJobTmpJarsAndFiles(Job job, String kylinDependency) {
        if (StringUtils.isBlank(kylinDependency))
            return;

        String[] fNameList = kylinDependency.split(",");

        try {
            Configuration jobConf = job.getConfiguration();
            FileSystem fs = FileSystem.getLocal(jobConf);

            StringBuilder jarList = new StringBuilder();
            StringBuilder fileList = new StringBuilder();

            for (String fileName : fNameList) {
                Path p = new Path(fileName);
                if (fs.getFileStatus(p).isDirectory()) {
                    appendTmpDir(job, fileName);
                    continue;
                }

                StringBuilder list = (p.getName().endsWith(".jar")) ? jarList : fileList;
                if (list.length() > 0)
                    list.append(",");
                list.append(fs.getFileStatus(p).getPath().toString());
            }

            appendTmpFiles(fileList.toString(), jobConf);
            appendTmpJars(jarList.toString(), jobConf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void appendTmpDir(Job job, String tmpDir) {
        if (StringUtils.isBlank(tmpDir))
            return;

        try {
            Configuration jobConf = job.getConfiguration();
            FileSystem fs = FileSystem.getLocal(jobConf);
            FileStatus[] fList = fs.listStatus(new Path(tmpDir));

            StringBuilder jarList = new StringBuilder();
            StringBuilder fileList = new StringBuilder();

            for (FileStatus file : fList) {
                Path p = file.getPath();
                if (fs.getFileStatus(p).isDirectory()) {
                    appendTmpDir(job, p.toString());
                    continue;
                }

                StringBuilder list = (p.getName().endsWith(".jar")) ? jarList : fileList;
                if (list.length() > 0)
                    list.append(",");
                list.append(fs.getFileStatus(p).getPath().toString());
            }

            appendTmpFiles(fileList.toString(), jobConf);
            appendTmpJars(jarList.toString(), jobConf);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void appendTmpJars(String jarList, Configuration conf) {
        if (StringUtils.isBlank(jarList))
            return;

        String tmpJars = conf.get("tmpjars", null);
        if (tmpJars == null) {
            tmpJars = jarList;
        } else {
            tmpJars += "," + jarList;
        }
        conf.set("tmpjars", tmpJars);
        logger.info("Job 'tmpjars' updated -- " + tmpJars);
    }

    private void appendTmpFiles(String fileList, Configuration conf) {
        if (StringUtils.isBlank(fileList))
            return;

        String tmpFiles = conf.get("tmpfiles", null);
        if (tmpFiles == null) {
            tmpFiles = fileList;
        } else {
            tmpFiles += "," + fileList;
        }
        conf.set("tmpfiles", tmpFiles);
        logger.info("Job 'tmpfiles' updated -- " + tmpFiles);
    }

    private String getDefaultMapRedClasspath() {

        String classpath = "";
        try {
            CliCommandExecutor executor = KylinConfig.getInstanceFromEnv().getCliCommandExecutor();
            String output = executor.execute("mapred classpath").getSecond();
            classpath = output.trim().replace(':', ',');
        } catch (IOException e) {
            logger.error("Failed to run: 'mapred classpath'.", e);
        }

        return classpath;
    }

    public static void addInputDirs(String input, Job job) throws IOException {
        addInputDirs(StringSplitter.split(input, ","), job);
    }

    public static void addInputDirs(String[] inputs, Job job) throws IOException {
        for (String inp : inputs) {
            inp = inp.trim();
            if (inp.endsWith("/*")) {
                inp = inp.substring(0, inp.length() - 2);
                FileSystem fs = FileSystem.get(job.getConfiguration());
                Path path = new Path(inp);
                FileStatus[] fileStatuses = fs.listStatus(path);
                boolean hasDir = false;
                for (FileStatus stat : fileStatuses) {
                    if (stat.isDirectory() && !stat.getPath().getName().startsWith("_")) {
                        hasDir = true;
                        addInputDirs(stat.getPath().toString(), job);
                    }
                }
                if (fileStatuses.length > 0 && !hasDir) {
                    addInputDirs(path.toString(), job);
                }
            } else {
                logger.debug("Add input " + inp);
                FileInputFormat.addInputPath(job, new Path(inp));
            }
        }
    }

    public static KylinConfig loadKylinPropsAndMetadata() throws IOException {
        File metaDir = new File("meta");
        if (!metaDir.getAbsolutePath().equals(System.getProperty(KylinConfig.KYLIN_CONF))) {
            System.setProperty(KylinConfig.KYLIN_CONF, metaDir.getAbsolutePath());
            logger.info("The absolute path for meta dir is " + metaDir.getAbsolutePath());
            KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
            kylinConfig.setMetadataUrl(metaDir.getAbsolutePath());
            return kylinConfig;
        } else {
            return KylinConfig.getInstanceFromEnv();
        }
    }

    protected void attachKylinPropsAndMetadata(TableDesc table, Configuration conf) throws IOException {
        ArrayList<String> dumpList = new ArrayList<String>();
        dumpList.add(table.getResourcePath());
        attachKylinPropsAndMetadata(dumpList, KylinConfig.getInstanceFromEnv(), conf);
    }

    protected void attachKylinPropsAndMetadata(CubeInstance cube, Configuration conf) throws IOException {
        MetadataManager metaMgr = MetadataManager.getInstance(cube.getConfig());

        // write cube / model_desc / cube_desc / dict / table
        ArrayList<String> dumpList = new ArrayList<String>();
        dumpList.add(cube.getResourcePath());
        dumpList.add(cube.getDescriptor().getModel().getResourcePath());
        dumpList.add(cube.getDescriptor().getResourcePath());

        for (String tableName : cube.getDescriptor().getModel().getAllTables()) {
            TableDesc table = metaMgr.getTableDesc(tableName);
            dumpList.add(table.getResourcePath());
        }
        for (CubeSegment segment : cube.getSegments()) {
            dumpList.addAll(segment.getDictionaryPaths());
        }

        attachKylinPropsAndMetadata(dumpList, cube.getConfig(), conf);
    }

    protected void attachKylinPropsAndMetadata(IIInstance ii, Configuration conf) throws IOException {
        MetadataManager metaMgr = MetadataManager.getInstance(ii.getConfig());

        // write II / model_desc / II_desc / dict / table
        ArrayList<String> dumpList = new ArrayList<String>();
        dumpList.add(ii.getResourcePath());
        dumpList.add(ii.getDescriptor().getModel().getResourcePath());
        dumpList.add(ii.getDescriptor().getResourcePath());

        for (String tableName : ii.getDescriptor().getModel().getAllTables()) {
            TableDesc table = metaMgr.getTableDesc(tableName);
            dumpList.add(table.getResourcePath());
        }

        attachKylinPropsAndMetadata(dumpList, ii.getConfig(), conf);
    }

    protected void attachKylinPropsAndMetadata(ArrayList<String> dumpList, KylinConfig kylinConfig, Configuration conf) throws IOException {
        File tmp = File.createTempFile("kylin_job_meta", "");
        FileUtils.forceDelete(tmp); // we need a directory, so delete the file first

        File metaDir = new File(tmp, "meta");
        metaDir.mkdirs();

        // write kylin.properties
        File kylinPropsFile = new File(metaDir, "kylin.properties");
        kylinConfig.writeProperties(kylinPropsFile);

        // write resources
        dumpResources(kylinConfig, metaDir, dumpList);

        // hadoop distributed cache
        String hdfsMetaDir = OptionsHelper.convertToFileURL(metaDir.getAbsolutePath());
        if (hdfsMetaDir.startsWith("/")) // note Path on windows is like "d:/../..."
            hdfsMetaDir = "file://" + hdfsMetaDir;
        else
            hdfsMetaDir = "file:///" + hdfsMetaDir;
        logger.info("HDFS meta dir is: " + hdfsMetaDir);

        appendTmpFiles(hdfsMetaDir, conf);
    }

    protected void cleanupTempConfFile(Configuration conf) {
        String tempMetaFileString = conf.get("tmpfiles");
        logger.info("tempMetaFileString is : " + tempMetaFileString);
        if (tempMetaFileString != null) {
            if (tempMetaFileString.startsWith("file://")) {
                tempMetaFileString = tempMetaFileString.substring("file://".length());
                File tempMetaFile = new File(tempMetaFileString);
                if (tempMetaFile.exists()) {
                    try {
                        FileUtils.forceDelete(tempMetaFile.getParentFile());

                    } catch (IOException e) {
                        logger.warn("error when deleting " + tempMetaFile, e);
                    }
                } else {
                    logger.info("" + tempMetaFileString + " does not exist");
                }
            } else {
                logger.info("tempMetaFileString is not starting with file:// :" + tempMetaFileString);
            }
        }
    }

    private void dumpResources(KylinConfig kylinConfig, File metaDir, ArrayList<String> dumpList) throws IOException {
        ResourceStore from = ResourceStore.getStore(kylinConfig);
        KylinConfig localConfig = KylinConfig.createInstanceFromUri(metaDir.getAbsolutePath());
        ResourceStore to = ResourceStore.getStore(localConfig);
        for (String path : dumpList) {
            RawResource res = from.getResource(path);
            if (res == null)
                throw new IllegalStateException("No resource found at -- " + path);
            to.putResource(path, res.inputStream, res.timestamp);
            res.inputStream.close();
        }
    }

    protected void deletePath(Configuration conf, Path path) throws IOException {
        HadoopUtil.deletePath(conf, path);
    }

    protected double getTotalMapInputMB() throws ClassNotFoundException, IOException, InterruptedException, JobException {
        if (job == null) {
            throw new JobException("Job is null");
        }

        long mapInputBytes = 0;
        InputFormat<?, ?> input = ReflectionUtils.newInstance(job.getInputFormatClass(), job.getConfiguration());
        for (InputSplit split : input.getSplits(job)) {
            mapInputBytes += split.getLength();
        }
        if (mapInputBytes == 0) {
            throw new IllegalArgumentException("Map input splits are 0 bytes, something is wrong!");
        }
        double totalMapInputMB = (double) mapInputBytes / 1024 / 1024;
        return totalMapInputMB;
    }

    protected int getMapInputSplitCount() throws ClassNotFoundException, JobException, IOException, InterruptedException {
        if (job == null) {
            throw new JobException("Job is null");
        }
        InputFormat<?, ?> input = ReflectionUtils.newInstance(job.getInputFormatClass(), job.getConfiguration());
        return input.getSplits(job).size();
    }

    public void kill() throws JobException {
        if (job != null) {
            try {
                job.killJob();
            } catch (IOException e) {
                throw new JobException(e);
            }
        }
    }

    public Map<String, String> getInfo() throws JobException {
        if (job != null) {
            Map<String, String> status = new HashMap<String, String>();
            if (null != job.getJobID()) {
                status.put(JobInstance.MR_JOB_ID, job.getJobID().toString());
            }
            if (null != job.getTrackingURL()) {
                status.put(JobInstance.YARN_APP_URL, job.getTrackingURL().toString());
            }

            return status;
        } else {
            throw new JobException("Job is null");
        }
    }

    public Counters getCounters() throws JobException {
        if (job != null) {
            try {
                return job.getCounters();
            } catch (IOException e) {
                throw new JobException(e);
            }
        } else {
            throw new JobException("Job is null");
        }
    }

    public void setAsync(boolean isAsync) {
        this.isAsync = isAsync;
    }

    public Job getJob() {
        return this.job;
    }

    // tells MapReduceExecutable to skip this job
    public boolean isSkipped() {
        return false;
    }

}
