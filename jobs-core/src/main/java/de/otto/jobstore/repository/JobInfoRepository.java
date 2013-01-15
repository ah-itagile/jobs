package de.otto.jobstore.repository;

import com.mongodb.*;
import de.otto.jobstore.common.*;
import de.otto.jobstore.common.properties.JobInfoProperty;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/**
 * A repository which stores information on jobs. For each distinct job name only one job can be running or queued.
 *
 * The method {@link #cleanupTimedOutJobs} needs to be called regularly to remove possible timed out jobs which would
 * otherwise stop new jobs from being able to execute.
 */
public class JobInfoRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobInfoRepository.class);

    private static final String JOB_NAME_CLEANUP              = "JobInfo_Cleanup";
    private static final String JOB_NAME_TIMED_OUT_CLEANUP    = "JobInfo_TimedOut_Cleanup";
    private static final String JOB_NAME_CLEANUP_NOT_EXECUTED = "JobInfo_NotExecuted_Cleanup";

    private final DBCollection collection;

    private int hoursAfterWhichOldJobsAreDeleted         = 7 * 24;
    private int hoursAfterWhichNotExecutedJobsAreDeleted = 4;


    public JobInfoRepository(final Mongo mongo, final String dbName, final String collectionName) {
        this(mongo, dbName, collectionName, null, null);
    }

    public JobInfoRepository(final Mongo mongo, final String dbName, final String collectionName, final String username, final String password) {
        final DB db = mongo.getDB(dbName);
        if (username != null && !username.isEmpty()) {
            if (!db.isAuthenticated()) {
                final boolean authenticateSuccess = db.authenticate(username, password.toCharArray());
                if (!authenticateSuccess) {
                    throw new RuntimeException("The authentication at the database: " + dbName + " on the host: " +
                            mongo.getAddress() + " with the username: " + username + " and the given password was not successful");
                } else {
                    LOGGER.info("Login at database {} on the host {} was successful", dbName, mongo.getAddress());
                }
            }
        }
        collection = db.getCollection(collectionName);
        LOGGER.info("Prepare access to MongoDB collection '{}' on {}/{}", new Object[]{collectionName, mongo, dbName});
        prepareCollection();
    }

    public int getHoursAfterWhichOldJobsAreDeleted() {
        return hoursAfterWhichOldJobsAreDeleted;
    }

    /**
     * Sets the number of hours after which old jobs are removed.
     * Default value is 7 days.
     *
     * @param hours The number of hours
     */
    public void setHoursAfterWhichOldJobsAreDeleted(int hours) {
        this.hoursAfterWhichOldJobsAreDeleted = hours;
    }

    /**
     * Creates a new job with the given parameters. Host and thread executing the job are determined automatically.
     *
     * @param name The name of the job
     * @param maxExecutionTime Sets the time after which a job is considered to be dead (lastModifiedTime + timeout).
     * @param runningState The state with which the job is started
     * @param executionPriority The priority with which the job is to be executed
     * @param additionalData Additional information to be stored with the job
     * @return The id of the job if it could be created or null if a job with the same name and state already exists
     */
    public String create(final String name, final long maxExecutionTime, final RunningState runningState,
                         final JobExecutionPriority executionPriority, final Map<String, String> parameters, final Map<String, String> additionalData) {
        final String host = InternetUtils.getHostName();
        final String thread = Thread.currentThread().getName();
        return create(name, host, thread, maxExecutionTime, runningState, executionPriority, parameters, additionalData);
    }

    /**
     * Creates a new job with the given parameters
     *
     * @param name The name of the job
     * @param host The host, on which the job is running
     * @param thread The thread, which runs the job
     * @param maxExecutionTime Sets the time after which a job is considered to be dead (lastModifiedTime + timeout).
     * @param runningState The state with which the job is started
     * @param executionPriority The priority with which the job is to be executed
     * @param additionalData Additional information to be stored with the job
     * @return The id of the job if it could be created or null if a job with the same name and state already exists
     */
    public String create(final String name, final String host, final String thread, final long maxExecutionTime,
                         final RunningState runningState, final JobExecutionPriority executionPriority, 
                         final Map<String, String> parameters, final Map<String, String> additionalData) {
        try {
            LOGGER.info("Create job={} in state={} ...", name, runningState);
            final JobInfo jobInfo = new JobInfo(name, host, thread, maxExecutionTime, runningState, executionPriority, additionalData);
            jobInfo.setParameters(parameters);
            save(jobInfo, WriteConcern.SAFE);
            return jobInfo.getId();
        } catch (MongoException.DuplicateKey e) {
            LOGGER.warn("job={} with state={} already exists, creation skipped!", name, runningState);
            return null;
        }
    }

    /**
     * Returns job with the given name and running state
     *
     * @param name The name of the job
     * @param runningState The running state of the job
     * @return The running job or null if no job with the given name is currently running
     */
    public JobInfo findByNameAndRunningState(final String name, final RunningState runningState) {
        final DBObject jobInfo = collection.findOne(createFindByNameAndRunningStateQuery(name, runningState.name()));
        return fromDbObject(jobInfo);
    }

    /**
     * Checks if a job with the given name and state exists
     *
     * @param name The name of the job
     * @param runningState The running state of the job
     * @return true - A job with the given name is still running<br/>
     *          false - A job with the given name is not running
     */
    public boolean hasJob(final String name, final RunningState runningState) {
        return findByNameAndRunningState(name, runningState) != null;
    }

    /**
     * Returns all queued jobs sorted ascending by start time
     *
     * @return The queued jobs
     */
    public List<JobInfo> findQueuedJobsSortedAscByCreationTime() {
        final DBCursor cursor = collection.find(new BasicDBObject(JobInfoProperty.RUNNING_STATE.val(), RunningState.QUEUED.name())).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.ASC.val()));
        return getAll(cursor);
    }

    /**
     * Returns a list of jobs with the given name which have a creation timestamp which is in between the supplied
     * dates. If the start and end parameter are null, the result list will contain all jobs with the supplied name.
     *
     * @param name The name of the jobs to return
     * @param start The date on or after which the jobs were created
     * @param end The date on or before which the jobs were created
     * @param resultCode Limit to the jobs with the specified result state
     * @return The list of jobs sorted by creationTime in descending order
     */
    public List<JobInfo> findByNameAndTimeRange(final String name, final Date start, final Date end, final ResultCode resultCode) {
        final BasicDBObjectBuilder query = new BasicDBObjectBuilder().append(JobInfoProperty.NAME.val(), name);
        if (start != null) {
            query.append(JobInfoProperty.CREATION_TIME.val(), new BasicDBObject(MongoOperator.GTE.op(), start));
        }
        if (end != null) {
            query.append(JobInfoProperty.CREATION_TIME.val(), new BasicDBObject(MongoOperator.LTE.op(), end));
        }
        if (resultCode != null) {
            query.append(JobInfoProperty.RESULT_STATE.val(), resultCode.name());
        }
        final DBCursor cursor = collection.find(query.get()).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.DESC.val()));
        return getAll(cursor);
    }

    /**
     * Sets the status of the queued job with the given name to running. The lastModified date of the job is set
     * to the current date.
     *
     * @param name The name of the job
     * @return true - If the job with the given name was activated successfully<br/>
     *         false - If no queued job with the current name could be found and thus could not activated
     */
    public boolean activateQueuedJob(final String name) {
        Date dt = new Date();
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(),
                new BasicDBObject(JobInfoProperty.RUNNING_STATE.val(), RunningState.RUNNING.name()).
                        append(JobInfoProperty.START_TIME.val(), dt).
                        append(JobInfoProperty.LAST_MODIFICATION_TIME.val(), dt));
        LOGGER.info("Activate queued job={} ...", name);
        try{
            final WriteResult result = collection.update(createFindByNameAndRunningStateQuery(name, RunningState.QUEUED.name()), update, false, false, WriteConcern.SAFE);
            return result.getN() == 1;
        }catch(MongoException.DuplicateKey e){
            return false;
        }
    }

    /**
     * Updates the host and thread information on the running job with the given name. Host and thread information
     * are determined automatically.
     * The processing of this method is performed asynchronously. Thus the existance of a running job with the given
     * jobname ist not checked
     *
     * @param name The name of the job
     */
    public void updateHostThreadInformation(final String name) {
        updateHostThreadInformation(name, InternetUtils.getHostName(), Thread.currentThread().getName());
    }

    /**
     * Updates the host and thread information on the running job with the given name
     * The processing of this method is performed asynchronously. Thus the existance of a running job with the given
     * jobname ist not checked
     *
     * @param name The name of the job
     * @param host The host to set
     * @param thread The thread to set
     */
    public void updateHostThreadInformation(final String name, final String host, final String thread) {
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(),
                new BasicDBObject(JobInfoProperty.HOST.val(), host).append(JobInfoProperty.THREAD.val(), thread));
        collection.update(createFindByNameAndRunningStateQuery(name, RunningState.RUNNING.name()), update);
    }

    /**
     * Marks a running job with the given name as finished.
     *
     * @param query The query of to find object to update
     * @param resultCode The result state of the job
     * @param resultMessage An optional error message
     * @return true - The job was marked as requested<br/>
     *         false - No running job with the given name could be found
     */
    private boolean markRunningAsFinished(final DBObject query, final ResultCode resultCode, final String resultMessage) {
        final Date dt = new Date();
        final BasicDBObjectBuilder set = new BasicDBObjectBuilder().
                append(JobInfoProperty.RUNNING_STATE.val(), createFinishedRunningState()).
                append(JobInfoProperty.LAST_MODIFICATION_TIME.val(), dt).
                append(JobInfoProperty.FINISH_TIME.val(), dt).
                append(JobInfoProperty.RESULT_STATE.val(), resultCode.name());
        if (resultMessage != null) {
            set.append(JobInfoProperty.RESULT_MESSAGE.val(), resultMessage);
        }
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(), set.get());
        final WriteResult result = collection.update(query, update, false, false, WriteConcern.SAFE);
        return result.getN() == 1;
    }

    /**
     * Marks a running job with the given name as finished.
     *
     * @param id The id of the job
     * @param resultCode The result state of the job
     * @param t An exception
     * @return true - The job was marked as requested<br/>
     *         false - No running job with the given name could be found
     */
    public boolean markAsFinishedById(final String id, final ResultCode resultCode, final Throwable t) {
        return ObjectId.isValid(id) &&
                markRunningAsFinished(new BasicDBObject(JobInfoProperty.ID.val(), new ObjectId(id)),
                        resultCode, t == null ? null : exceptionToMessage(t));
    }

    /**
     * Marks a running job with the given name as finished.
     *
     * @param name The name of the job
     * @param resultCode The result state of the job
     * @param resultMessage An optional error message
     * @return true - The job was marked as requested<br/>
     *         false - No running job with the given name could be found
     */
    public boolean markRunningAsFinished(final String name, final ResultCode resultCode, final String resultMessage) {
        return markRunningAsFinished(createFindByNameAndRunningStateQuery(name, RunningState.RUNNING.name()),
                resultCode, resultMessage);
    }

    /**
     * Marks a running job with the given name as finished with an error and writes
     * the stack trace of the exception to the error message property of the job.
     *
     * @param name The name of the job
     * @return true - The job was marked as requested<br/>
     *          false - No running job with the given name could be found
     */
    public boolean markRunningAsFinishedWithException(final String name, final Throwable t) {
        final StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return markRunningAsFinished(name, ResultCode.FAILED, exceptionToMessage(t));
    }

    /**
     * Marks the current queued job of the given name as not executed
     *
     * @param name The name of the job
     * @return true - The job was marked as requestred<br/>
     *          false - No queued job with the given name could be found
     */
    public boolean markQueuedAsNotExecuted(final String name) {
        final Date dt = new Date();
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(),
                new BasicDBObject().append(JobInfoProperty.RESULT_STATE.val(), ResultCode.NOT_EXECUTED.name()).
                        append(JobInfoProperty.LAST_MODIFICATION_TIME.val(), dt).
                        append(JobInfoProperty.FINISH_TIME.val(), dt).
                        append(JobInfoProperty.RUNNING_STATE.val(), createFinishedRunningState()));
        final WriteResult result = collection.update(new BasicDBObject().append(JobInfoProperty.NAME.val(), name).
                append(JobInfoProperty.RUNNING_STATE.val(), RunningState.QUEUED.name()), update, false, false, WriteConcern.SAFE);
        return result.getN() == 1;
    }

    /**
     * Marks a job with the given name as finished successfully.
     *
     * @param name The name of the job
     * @return true - The job was marked as requested<br/>
     *          false - No running job with the given name could be found
     */
    public boolean markRunningAsFinishedSuccessfully(final String name) {
        return markRunningAsFinished(name, ResultCode.SUCCESSFUL, null);
    }

    /**
     * Adds additional data to a running job with the given name. If information with the given key already exists
     * it is overwritten. The lastModified date of the job is set to the current date.
     *
     * The processing of this method is performed asynchronously. Thus the existance of a running job with the given
     * jobname ist not checked.
     *
     * @param name The name of the job
     * @param key The key of the data to save
     * @param value The information to save
     */
    public void addAdditionalData(final String name, final String key, final String value) {
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(),
                new BasicDBObjectBuilder().append(JobInfoProperty.LAST_MODIFICATION_TIME.val(), new Date()).
                        append(JobInfoProperty.ADDITIONAL_DATA.val() + "." + key, value).get());
        collection.update(createFindByNameAndRunningStateQuery(name, RunningState.RUNNING.name()), update);
    }

    /**
     * Sets a status message.
     *
     * The processing of this method is performed asynchronously. Thus the existance of a running job with the given
     * jobname ist not checked
     *
     * @param name The name of the job
     * @param message The message to set
     */
    public void setStatusMessage(final String name, String message) {
        final DBObject update = new BasicDBObject().append(MongoOperator.SET.op(),
                new BasicDBObjectBuilder().append(JobInfoProperty.LAST_MODIFICATION_TIME.val(), new Date()).
                        append(JobInfoProperty.STATUS_MESSAGE.val(), message).get());
        collection.update(createFindByNameAndRunningStateQuery(name, RunningState.RUNNING.name()), update);
    }

    /**
     * Find a job by its id.
     *
     * @param id The id of the job
     * @return The job with the given id or null if no corresponding job was found.
     */
    public JobInfo findById(final String id) {
        if (ObjectId.isValid(id)) {
            final DBObject obj = collection.findOne(new BasicDBObject("_id", new ObjectId(id)));
            return fromDbObject(obj);
        } else {
            return null;
        }
    }

    /**
     * Returns all jobs with the given name.
     *
     * @param name The name of the jobs
     * @param limit The maximum number of jobs to return
     * @return All jobs with the given name sorted descending by last modified date
     */
    public List<JobInfo> findByName(final String name, final Integer limit) {
        final BasicDBObjectBuilder query = new BasicDBObjectBuilder().append(JobInfoProperty.NAME.val(), name);
        final DBCursor cursor = collection.find(query.get()).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.DESC.val()));
        if (limit == null) {
            return getAll(cursor);
        } else {
            return getAll(cursor.limit(limit));
        }
    }

    /**
     * Returns the job with the given name and the most current last modified timestamp.
     *
     * @param name The name of the job
     * @return The job with the given name and the most current timestamp or null if none could be found.
     */
    public JobInfo findMostRecent(final String name) {
        final DBCursor cursor = collection.find(new BasicDBObject().
                append(JobInfoProperty.NAME.val(), name)).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.DESC.val())).limit(1);
        return getFirst(cursor);
    }

    public JobInfo findMostRecentFinished(String name) {
        final List<String> resultStates = toStringList(EnumSet.complementOf(EnumSet.of(RunningState.FINISHED)));
        final DBCursor cursor = collection.find(new BasicDBObject().
                append(JobInfoProperty.NAME.val(), name).
                append(JobInfoProperty.RUNNING_STATE.val(), new BasicDBObject(MongoOperator.NIN.op(), resultStates))).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.DESC.val())).limit(1);
        return getFirst(cursor);
    }

    /**
     * Returns the job with the given name and result state(s) as well as the most current last modified timestamp.
     *
     * @param name The name of the job
     * @param resultStates The result states the job may have
     * @return The job with the given name and result state as well as the most current timestamp or null
     * if none could be found.
     */
    public JobInfo findMostRecentByNameAndResultState(final String name, final Set<ResultCode> resultStates) {
        DBObject query = createFindByNameAndResultStateQuery(name, resultStates);
        DBCursor cursor = collection.find(query).
                sort(new BasicDBObject(JobInfoProperty.CREATION_TIME.val(), SortOrder.DESC.val())).limit(1);
        return getFirst(cursor);
    }

    /**
     * Returns for all existing job names the job with the most current last modified timestamp regardless of its state.
     *
     * @return The jobs with distinct names and the most current last modified timestamp
     */
    public List<JobInfo> findMostRecent() {
        final List<JobInfo> jobs = new ArrayList<>();
        for (String name : distinctJobNames()) {
            final JobInfo jobInfo = findMostRecent(name);
            if (jobInfo != null) {
                jobs.add(jobInfo);
            }
        }
        return jobs;
    }

    /**
     * Returns the list of all distinct job names within this repository
     *
     * @return The list of distinct jobnames
     */
    @SuppressWarnings("unchecked")
    public List<String> distinctJobNames() {
        return collection.distinct(JobInfoProperty.NAME.val());
    }

    /**
     * Adds a logging line to the logging data of the running job with the supplied name
     * The processing of this method is performed asynchronously. Thus the existance of a running job with the given
     * jobname ist not checked
     *
     * @param jobName The name of the job
     * @param line The log line to add
     */
    public void addLogLine(final String jobName, final String line) {
        final Date dt = new Date();
        final LogLine logLine = new LogLine(line, dt);
        final DBObject update = new BasicDBObject().
                append(MongoOperator.PUSH.op(), new BasicDBObject(JobInfoProperty.LOG_LINES.val(), logLine.toDbObject())).
                append(MongoOperator.SET.op(), new BasicDBObject(JobInfoProperty.LAST_MODIFICATION_TIME.val(), dt));
        collection.update(createFindByNameAndRunningStateQuery(jobName, RunningState.RUNNING.name()), update);
    }

    /**
     * Appends the log lines of the running job with the supplies name
     * to the already existing log lines.
     *
     * @param name The name of the job
     * @param lines the log lines to add
     * @return true - The data was successfully added to the job<br/>
     *         false - No running job with the given name could be found
     */
    public boolean appendLogLines(final String name, final List<String> lines) {
        final Date dt = new Date();
        final List<DBObject> logLines = new ArrayList<>();
        for (String line : lines) {
            logLines.add(new LogLine(line, dt).toDbObject());
        }
        final DBObject update = new BasicDBObject().
                append("$pushAll", new BasicDBObject(JobInfoProperty.LOG_LINES.val(), logLines)).
                append(MongoOperator.SET.op(), new BasicDBObject(JobInfoProperty.LAST_MODIFICATION_TIME.val(), dt));
        final WriteResult result = collection.update(createFindByNameAndRunningStateQuery(name, RunningState.RUNNING.name()), update, false, false, WriteConcern.SAFE);
        return result.getN() == 1;
    }

    /**
     * Removed the running job (flag it as timed out) with the given name if it is timed out
     *
     * @param name The name of the job
     * @param currentDate The current date
     */
    public void removeJobIfTimedOut(final String name, final Date currentDate) {
        if (hasJob(name, RunningState.RUNNING)) {
            final JobInfo job = findByNameAndRunningState(name, RunningState.RUNNING);
            if (job.isTimedOut(currentDate)) {
                markRunningAsFinished(job.getName(), ResultCode.TIMED_OUT, null);
            }
        }
    }

    /**
     * Clears all elements from the repository
     *
     * @param dropCollection Flag if the collection should be dropped
     */
    public void clear(final boolean dropCollection) {
        LOGGER.info("Going to clear all entities on collection: {}", collection.getFullName());
        if (dropCollection) {
            collection.drop();
            prepareCollection();
        } else {
            final WriteResult wr = collection.remove(new BasicDBObject());
            final CommandResult cr = wr.getLastError(WriteConcern.SAFE);
            if (cr.ok()) {
                LOGGER.info("Cleared all entities successfully on collection: {}", collection.getFullName());
            } else {
                LOGGER.error("Could not clear entities on collection {}: {}", collection.getFullName(), cr.getErrorMessage());
            }
        }
    }

    public void remove(final String id) {
        if (ObjectId.isValid(id)) {
            collection.remove(new BasicDBObject(JobInfoProperty.ID.val(), new ObjectId(id)), WriteConcern.SAFE);
        }
    }

    /**
     * Counts the number of documents in the repository.
     *
     * @return The number of documents in the repository
     */
    public long count() {
        return collection.count();
    }

    /**
     * Flags all running jobs as timed out if the have not be updated within the max execution time
     */
    public int cleanupTimedOutJobs() {
        final Date currentDate = new Date();
        removeJobIfTimedOut(JOB_NAME_TIMED_OUT_CLEANUP, currentDate);
        int numberOfRemovedJobs = 0;
        if (!hasJob(JOB_NAME_TIMED_OUT_CLEANUP, RunningState.RUNNING)) {
            create(JOB_NAME_TIMED_OUT_CLEANUP, 5 * 60 * 1000, RunningState.RUNNING, JobExecutionPriority.CHECK_PRECONDITIONS, null, null);
            final DBCursor cursor = collection.find(new BasicDBObject(JobInfoProperty.RUNNING_STATE.val(), RunningState.RUNNING.name()));
            final List<String> removedJobs = new ArrayList<>();
            for (JobInfo jobInfo : getAll(cursor)) {
                if (jobInfo.isTimedOut(currentDate)) {
                    if (markRunningAsFinished(jobInfo.getName(), ResultCode.TIMED_OUT, null)) {
                        removedJobs.add(jobInfo.getName() + " - " + jobInfo.getId());
                        ++numberOfRemovedJobs;
                    }
                }
            }
            addAdditionalData(JOB_NAME_TIMED_OUT_CLEANUP, "numberOfRemovedJobs", String.valueOf(numberOfRemovedJobs));
            if (!removedJobs.isEmpty()) {
                addAdditionalData(JOB_NAME_TIMED_OUT_CLEANUP, "removedJobs", removedJobs.toString());
            }
            markRunningAsFinishedSuccessfully(JOB_NAME_TIMED_OUT_CLEANUP);
        }
        return numberOfRemovedJobs;
    }

    public int cleanupOldJobs() {
        final Date currentDate = new Date();
        removeJobIfTimedOut(JOB_NAME_CLEANUP, currentDate);
        int numberOfRemovedJobs = 0;
        if (!hasJob(JOB_NAME_CLEANUP, RunningState.RUNNING)) {
            /* register clean up job with max execution time */
            long maxExecutionTime = 5 * 60 * 1000;
            create(JOB_NAME_CLEANUP, maxExecutionTime, RunningState.RUNNING, JobExecutionPriority.CHECK_PRECONDITIONS, null, null);
            Date beforeDate = new Date(currentDate.getTime() - (Math.min(4, hoursAfterWhichOldJobsAreDeleted) * 60 * 60 * 1000));
            LOGGER.info("Going to delete not runnnig jobs before {} ...", beforeDate);
            /* ... good bye ... */
            numberOfRemovedJobs = cleanupNotRunning(beforeDate);
            addAdditionalData(JOB_NAME_CLEANUP, "numberOfRemovedJobs", String.valueOf(numberOfRemovedJobs));
            markRunningAsFinishedSuccessfully(JOB_NAME_CLEANUP);
        }
        return numberOfRemovedJobs;
    }

    public int cleanupNotExecutedJobs() {
        final Date currentDate = new Date();
        removeJobIfTimedOut(JOB_NAME_CLEANUP_NOT_EXECUTED, currentDate);
        int numberOfRemovedJobs = 0;
        if (!hasJob(JOB_NAME_CLEANUP_NOT_EXECUTED, RunningState.RUNNING)) {
            /* register clean up job with max execution time */
            long maxExecutionTime = 5 * 60 * 1000;
            create(JOB_NAME_CLEANUP_NOT_EXECUTED, maxExecutionTime, RunningState.RUNNING, JobExecutionPriority.CHECK_PRECONDITIONS, null, null);
            Date beforeDate = new Date(currentDate.getTime() - (Math.min(1, hoursAfterWhichNotExecutedJobsAreDeleted) * 60 * 60 * 1000));
            LOGGER.info("Going to delete not executed jobs before {} ...", beforeDate);
            /* ... good bye ... */
            numberOfRemovedJobs = cleanupNotExecuted(beforeDate);
            addAdditionalData(JOB_NAME_CLEANUP_NOT_EXECUTED, "numberOfRemovedJobs", String.valueOf(numberOfRemovedJobs));
            markRunningAsFinishedSuccessfully(JOB_NAME_CLEANUP_NOT_EXECUTED);
        }
        return numberOfRemovedJobs;
    }

    // ~~

    protected void save(JobInfo jobInfo) {
        WriteResult wr = collection.save(jobInfo.toDbObject());
        final CommandResult cr = wr.getLastError(WriteConcern.SAFE);
        if (!cr.ok()) {
            LOGGER.error("Unable to save job info object id={}: " + wr, jobInfo.getId());
        }
    }

    protected void save(JobInfo jobInfo, WriteConcern writeConcern) {
        collection.save(jobInfo.toDbObject(), writeConcern);
    }

    protected int cleanupNotRunning(Date clearJobsBefore) {
        final WriteResult result = collection.remove(new BasicDBObject().
                append(JobInfoProperty.CREATION_TIME.val(), new BasicDBObject(MongoOperator.LT.op(), clearJobsBefore)).
                append(JobInfoProperty.RUNNING_STATE.val(), new BasicDBObject(MongoOperator.NE.op(), RunningState.RUNNING.name())),
                WriteConcern.SAFE);
        return result.getN();
    }

    protected int cleanupNotExecuted(Date clearJobsBefore) {
        final WriteResult result = collection.remove(new BasicDBObject().
                append(JobInfoProperty.CREATION_TIME.val(), new BasicDBObject(MongoOperator.LT.op(), clearJobsBefore)).
                append(JobInfoProperty.RESULT_STATE.val(), ResultCode.NOT_EXECUTED.name()).
                append(JobInfoProperty.RUNNING_STATE.val(), RunningState.FINISHED.name()),
                WriteConcern.SAFE);
        return result.getN();
    }

    private void prepareCollection() {
        collection.ensureIndex(new BasicDBObject(JobInfoProperty.NAME.val(), 1));
        collection.ensureIndex(new BasicDBObject(JobInfoProperty.LAST_MODIFICATION_TIME.val(), 1));
        collection.ensureIndex(new BasicDBObject().
                append(JobInfoProperty.RUNNING_STATE.val(), 1).append(JobInfoProperty.CREATION_TIME.val(), 1), "runningState_creationTime");
        collection.ensureIndex(new BasicDBObject().
                append(JobInfoProperty.NAME.val(), 1).append(JobInfoProperty.CREATION_TIME.val(), 1), "name_creationTime");
        collection.ensureIndex(new BasicDBObject().
                append(JobInfoProperty.NAME.val(), 1).append(JobInfoProperty.RUNNING_STATE.val(), 1), "name_state", true);
    }

    private JobInfo fromDbObject(final DBObject dbObject) {
        if (dbObject == null) {
            return null;
        }
        return new JobInfo(dbObject);
    }

    private List<JobInfo> getAll(final DBCursor cursor) {
        final List<JobInfo> elements = new ArrayList<>();
        while (cursor.hasNext()) {
            elements.add(fromDbObject(cursor.next()));
        }
        return elements;
    }

    private JobInfo getFirst(final DBCursor cursor) {
        if (cursor.hasNext()) {
            return fromDbObject(cursor.next());
        }
        return null;
    }

    private BasicDBObject createFindByNameAndRunningStateQuery(final String name, final String state) {
        return new BasicDBObject().append(JobInfoProperty.NAME.val(), name).
                append(JobInfoProperty.RUNNING_STATE.val(), state);
    }

    private DBObject createFindByNameAndResultStateQuery(final String name, final Set<ResultCode> states) {
        final List<String> resultStates = toStringList(states);
        return new BasicDBObject().append(JobInfoProperty.NAME.val(), name).
                append(JobInfoProperty.RESULT_STATE.val(), new BasicDBObject(MongoOperator.IN.op(), resultStates));
    }

    private String createFinishedRunningState() {
        return RunningState.FINISHED + "_" + UUID.randomUUID().toString();
    }

    private String exceptionToMessage(Throwable t) {
        final StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return "Problem: " + t.getMessage() + ", Stack-Trace: " + sw.toString();
    }

    private <E extends Enum<E>> List<String> toStringList(Set<E> enumSet) {
        final List<String> strings = new ArrayList<>();
        for (Enum e : enumSet) {
            strings.add(e.name());
        }
        return strings;
    }
}
