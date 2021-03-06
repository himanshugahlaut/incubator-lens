/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.server.query;

import static org.apache.lens.api.query.QueryStatus.Status.*;
import static org.apache.lens.server.api.LensConfConstants.*;
import static org.apache.lens.server.api.util.LensUtil.getImplementations;
import static org.apache.lens.server.session.LensSessionImpl.ResourceEntry;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.lens.api.LensConf;
import org.apache.lens.api.LensSessionHandle;
import org.apache.lens.api.error.ErrorCollection;
import org.apache.lens.api.query.*;
import org.apache.lens.api.query.QueryStatus.Status;
import org.apache.lens.api.result.LensErrorTO;
import org.apache.lens.driver.hive.HiveDriver;
import org.apache.lens.server.BaseLensService;
import org.apache.lens.server.LensServerConf;
import org.apache.lens.server.LensServices;
import org.apache.lens.server.api.driver.*;
import org.apache.lens.server.api.error.LensException;
import org.apache.lens.server.api.error.LensMultiCauseException;
import org.apache.lens.server.api.events.LensEventListener;
import org.apache.lens.server.api.health.HealthStatus;
import org.apache.lens.server.api.metrics.MethodMetricsContext;
import org.apache.lens.server.api.metrics.MethodMetricsFactory;
import org.apache.lens.server.api.metrics.MetricsService;
import org.apache.lens.server.api.query.*;
import org.apache.lens.server.api.query.collect.WaitingQueriesSelectionPolicy;
import org.apache.lens.server.api.query.constraint.QueryLaunchingConstraint;
import org.apache.lens.server.api.query.cost.QueryCost;
import org.apache.lens.server.model.LogSegregationContext;
import org.apache.lens.server.model.MappedDiagnosticLogSegregationContext;
import org.apache.lens.server.query.collect.*;
import org.apache.lens.server.query.constraint.DefaultQueryLaunchingConstraintsChecker;
import org.apache.lens.server.query.constraint.QueryLaunchingConstraintsChecker;
import org.apache.lens.server.rewrite.RewriteUtil;
import org.apache.lens.server.rewrite.UserQueryToCubeQueryRewriter;
import org.apache.lens.server.session.LensSessionImpl;
import org.apache.lens.server.stats.StatisticsService;
import org.apache.lens.server.util.FairPriorityBlockingQueue;
import org.apache.lens.server.util.UtilityMethods;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.service.cli.CLIService;
import org.apache.hive.service.cli.ColumnDescriptor;
import org.apache.hive.service.cli.TypeDescriptor;

import org.codehaus.jackson.*;
import org.codehaus.jackson.map.*;
import org.codehaus.jackson.map.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * The Class QueryExecutionServiceImpl.
 */
@Slf4j
public class QueryExecutionServiceImpl extends BaseLensService implements QueryExecutionService {

  /**
   * The Constant PREPARED_QUERIES_COUNTER.
   */
  public static final String PREPARED_QUERIES_COUNTER = "prepared-queries";

  /**
   * The Constant QUERY_SUBMITTER_COUNTER.
   */
  public static final String QUERY_SUBMITTER_COUNTER = "query-submitter-errors";

  /**
   * The Constant STATUS_UPDATE_COUNTER.
   */
  public static final String STATUS_UPDATE_COUNTER = "status-update-errors";

  /**
   * The Constant QUERY_PURGER_COUNTER.
   */
  public static final String QUERY_PURGER_COUNTER = "query-purger-errors";

  /**
   * The Constant PREPARED_QUERY_PURGER_COUNTER.
   */
  public static final String PREPARED_QUERY_PURGER_COUNTER = "prepared-query-purger-errors";

  /**
   * The millis in week.
   */
  private static long millisInWeek = 7 * 24 * 60 * 60 * 1000;

  /**
   * The Constant NAME.
   */
  public static final String NAME = "query";

  /**
   * The Constant MAPPER.
   */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The accepted queries.
   */
  private FairPriorityBlockingQueue<QueryContext> queuedQueries = new FairPriorityBlockingQueue<QueryContext>();

  /**
   * The launched queries.
   */
  private EstimatedQueryCollection launchedQueries;

  /**
   * The waiting queries.
   */
  private EstimatedQueryCollection waitingQueries;

  /**
   * The finished queries.
   */
  private DelayQueue<FinishedQuery> finishedQueries = new DelayQueue<FinishedQuery>();

  /**
   * The prepared query queue.
   */
  private DelayQueue<PreparedQueryContext> preparedQueryQueue = new DelayQueue<PreparedQueryContext>();

  /**
   * The prepared queries.
   */
  private Map<QueryPrepareHandle, PreparedQueryContext> preparedQueries
    = new HashMap<QueryPrepareHandle, PreparedQueryContext>();

  /**
   * The all queries.
   */
  protected ConcurrentMap<QueryHandle, QueryContext> allQueries = new ConcurrentHashMap<QueryHandle, QueryContext>();

  /**
   * The conf.
   */
  private Configuration conf;

  /**
   * The query submitter runnable.
   */
  private QuerySubmitter querySubmitterRunnable;

  /**
   * The query submitter.
   */
  protected Thread querySubmitter;

  /**
   * The status poller.
   */
  private final Thread statusPoller = new Thread(new StatusPoller(), "StatusPoller");

  /**
   * The query purger.
   */
  private final Thread queryPurger = new Thread(new QueryPurger(), "QueryPurger");

  /**
   * The prepare query purger.
   */
  private final Thread prepareQueryPurger = new Thread(new PreparedQueryPurger(), "PrepareQueryPurger");

  /**
   * The query acceptors.
   */
  private List<QueryAcceptor> queryAcceptors = new ArrayList<QueryAcceptor>();

  /**
   * The drivers.
   */
  private final Map<String, LensDriver> drivers = new HashMap<String, LensDriver>();

  /**
   * The driver selector.
   */
  private DriverSelector driverSelector;

  /**
   * The result sets.
   */
  private Map<QueryHandle, LensResultSet> resultSets = new HashMap<QueryHandle, LensResultSet>();

  /**
   * The metrics service.
   */
  private MetricsService metricsService;

  /**
   * The statistics service.
   */
  private StatisticsService statisticsService;

  /**
   * The max finished queries.
   */
  private int maxFinishedQueries;

  /**
   * The lens server dao.
   */
  LensServerDAO lensServerDao;

  /**
   * Thread pool used for running query estimates in parallel
   */
  private ExecutorService estimatePool;

  private final LogSegregationContext logSegregationContext;

  private final ErrorCollection errorCollection = LensServices.get().getErrorCollection();

  private QueryLaunchingConstraintsChecker queryConstraintsChecker;

  private WaitingQueriesSelector waitingQueriesSelector;

  /**
   * If the query taken out of queued queries is added to waiting, it will be re processed from waiting
   * queries, when the next launched query is finished and removed from launched queries. If a query is
   * removed from launched queries, while query not allowed to launch was still to be added to waiting
   * queries, then waiting query will have to wait for next launched query to finish.
   * This sort of delay in waiting query execution can be avoided if removal of a query from launched
   * queries is locked using removalFromLaunchedQueriesLock, until the decision to add to waiting queries
   * and actual addition to waiting query is complete.
   * */
  private final ReentrantLock removalFromLaunchedQueriesLock = new ReentrantLock();

  private final ExecutorService waitingQueriesSelectionSvc = Executors.newSingleThreadExecutor();

  /**
   * The driver event listener.
   */
  final LensEventListener<DriverEvent> driverEventListener = new LensEventListener<DriverEvent>() {
    @Override
    public void onEvent(DriverEvent event) {
      // Need to restore session only in case of hive driver
      if (event instanceof DriverSessionStarted) {
        log.info("New driver event by driver {}", event.getDriver());
        handleDriverSessionStart(event);
      }
    }
  };
  private UserQueryToCubeQueryRewriter userQueryToCubeQueryRewriter;


  /**
   * Instantiates a new query execution service impl.
   *
   * @param cliService the cli service
   * @throws LensException the lens exception
   */
  public QueryExecutionServiceImpl(CLIService cliService)
    throws LensException {
    super(NAME, cliService);
    this.logSegregationContext = new MappedDiagnosticLogSegregationContext();
  }

  /**
   * Initialize query acceptors and listeners.
   */
  private void initializeQueryAcceptors() throws LensException {
    String[] acceptorClasses = conf.getStrings(ACCEPTOR_CLASSES);
    if (acceptorClasses != null) {
      for (String acceptorClass : acceptorClasses) {
        try {
          Class<?> clazz = Class.forName(acceptorClass);
          QueryAcceptor acceptor = (QueryAcceptor) clazz.newInstance();
          log.info("initialized query acceptor: {}", acceptor);
          queryAcceptors.add(acceptor);
        } catch (Exception e) {
          log.warn("Could not load the acceptor: {}", acceptorClass, e);
          throw new LensException("Could not load acceptor" + acceptorClass, e);
        }
      }
    }
  }

  private void initializeListeners() {
    if (conf.getBoolean(QUERY_STATE_LOGGER_ENABLED, true)) {
      getEventService().addListenerForType(new QueryStatusLogger(), StatusChange.class);
      log.info("Registered query state logger");
    }
    // Add result formatter
    getEventService().addListenerForType(new ResultFormatter(this, this.logSegregationContext), QueryExecuted.class);
    getEventService().addListenerForType(new QueryExecutionStatisticsGenerator(this, getEventService()),
      QueryEnded.class);
    getEventService().addListenerForType(
      new QueryEndNotifier(this, getCliService().getHiveConf(), this.logSegregationContext), QueryEnded.class);
    log.info("Registered query result formatter");
  }

  /**
   * Load drivers and selector.
   *
   * @throws LensException the lens exception
   */
  private void loadDriversAndSelector() throws LensException {
    Class<?>[] driverClasses = conf.getClasses(DRIVER_CLASSES);
    if (driverClasses != null) {
      for (Class<?> driverClass : driverClasses) {
        try {
          LensDriver driver = (LensDriver) driverClass.newInstance();
          driver.configure(LensServerConf.getConf());
          if (driver instanceof HiveDriver) {
            driver.registerDriverEventListener(driverEventListener);
          }
          drivers.put(driverClass.getName(), driver);
          log.info("Driver for {} is loaded", driverClass);
        } catch (Exception e) {
          log.warn("Could not load the driver:{}", driverClass, e);
          throw new LensException("Could not load driver " + driverClass, e);
        }
      }
    } else {
      throw new LensException("No drivers specified");
    }
    try {
      Class<? extends DriverSelector> driverSelectorClass = conf.getClass(DRIVER_SELECTOR_CLASS,
        MinQueryCostSelector.class,
        DriverSelector.class);
      log.info("Using driver selector class: {}", driverSelectorClass.getCanonicalName());
      driverSelector = driverSelectorClass.newInstance();
    } catch (Exception e) {
      throw new LensException("Couldn't instantiate driver selector class. Class name: "
        + conf.get(DRIVER_SELECTOR_CLASS) + ". Please supply a valid value for "
        + DRIVER_SELECTOR_CLASS);
    }
  }

  private MetricsService getMetrics() {
    if (metricsService == null) {
      metricsService = LensServices.get().getService(MetricsService.NAME);
      if (metricsService == null) {
        throw new NullPointerException("Could not get metrics service");
      }
    }
    return metricsService;
  }

  private StatisticsService getStatisticsService() {
    if (statisticsService == null) {
      statisticsService = LensServices.get().getService(StatisticsService.STATS_SVC_NAME);
      if (statisticsService == null) {
        throw new NullPointerException("Could not get statistics service");
      }
    }
    return statisticsService;
  }

  /**
   * Incr counter.
   *
   * @param counter the counter
   */
  private void incrCounter(String counter) {
    getMetrics().incrCounter(QueryExecutionService.class, counter);
  }

  /**
   * Decr counter.
   *
   * @param counter the counter
   */
  private void decrCounter(String counter) {
    getMetrics().decrCounter(QueryExecutionService.class, counter);
  }

  /**
   * The Class QueryStatusLogger.
   */
  public static class QueryStatusLogger implements LensEventListener<StatusChange> {

    /**
     * The Constant STATUS_LOG.
     */
    public static final Logger STATUS_LOG = LoggerFactory.getLogger(QueryStatusLogger.class);

    /*
     * (non-Javadoc)
     *
     * @see org.apache.lens.server.api.events.LensEventListener#onEvent(org.apache.lens.server.api.events.LensEvent)
     */
    @Override
    public void onEvent(StatusChange event) throws LensException {
      STATUS_LOG.info(event.toString());
    }
  }

  /**
   * The Class FinishedQuery.
   */
  private class FinishedQuery implements Delayed {

    /**
     * The ctx.
     */
    private final QueryContext ctx;

    /**
     * The finish time.
     */
    private final Date finishTime;

    /**
     * Instantiates a new finished query.
     *
     * @param ctx the ctx
     */
    FinishedQuery(QueryContext ctx) {
      this.ctx = ctx;
      if (ctx.getEndTime() == 0) {
        this.finishTime = new Date();
        ctx.setEndTime(this.finishTime.getTime());
      } else {
        this.finishTime = new Date(ctx.getEndTime());
      }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(Delayed o) {
      return (int) (this.finishTime.getTime() - ((FinishedQuery) o).finishTime.getTime());
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.concurrent.Delayed#getDelay(java.util.concurrent.TimeUnit)
     */
    @Override
    public long getDelay(TimeUnit units) {
      int size = finishedQueries.size();
      if (size > maxFinishedQueries) {
        return 0;
      } else {
        return Integer.MAX_VALUE;
      }
    }

    /**
     * @return the finishTime
     */
    public Date getFinishTime() {
      return finishTime;
    }

    /**
     * @return the ctx
     */
    public QueryContext getCtx() {
      return ctx;
    }

    public String getQueryHandleString() {
      return ctx.getQueryHandleString();
    }
  }

  /**
   * The Class QuerySubmitter.
   */
  private class QuerySubmitter implements Runnable {

    /**
     * The paused for test.
     */
    private boolean pausedForTest = false;

    private final ErrorCollection errorCollection;

    private final EstimatedQueryCollection waitingQueries;

    private final QueryLaunchingConstraintsChecker constraintsChecker;

    public QuerySubmitter(@NonNull final ErrorCollection errorCollection,
        @NonNull final EstimatedQueryCollection waitingQueries,
        @NonNull final QueryLaunchingConstraintsChecker constraintsChecker) {

      this.errorCollection = errorCollection;
      this.waitingQueries = waitingQueries;
      this.constraintsChecker = constraintsChecker;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      log.info("Starting QuerySubmitter thread");
      while (!pausedForTest && !stopped && !querySubmitter.isInterrupted()) {
        try {
          QueryContext query = queuedQueries.take();
          synchronized (query) {

            /* Setting log segregation id */
            logSegregationContext.setLogSegragationAndQueryId(query.getQueryHandleString());

            if (!query.queued()) {
              log.info("Probably the query got cancelled. Skipping it. Query Status:{}", query.getStatus());
              continue;
            }

            log.info("Processing query:{}", query.getUserQuery());
            try {
              // acquire session before any query operation.
              acquire(query.getLensSessionIdentifier());

              /* Check javadoc of QueryExecutionServiceImpl#removalFromLaunchedQueriesLock for reason for existence
              of this lock. */
              log.debug("Acquiring lock in QuerySubmitter");
              removalFromLaunchedQueriesLock.lock();
              try {

                boolean isQueryAllowedToLaunch = this.constraintsChecker.canLaunch(query, launchedQueries);

                log.debug("isQueryAllowedToLaunch:{}", isQueryAllowedToLaunch);
                if (isQueryAllowedToLaunch) {

                  /* Query is not going to be added to waiting queries. No need to keep the lock.
                  First release lock, then launch query */
                  removalFromLaunchedQueriesLock.unlock();
                  launchQuery(query);
                } else {

                  /* Query is going to be added to waiting queries. Keep holding the lock to avoid any removal from
                  launched queries. First add to waiting queries, then release lock */
                  addToWaitingQueries(query);
                  removalFromLaunchedQueriesLock.unlock();
                }
              } finally {
                if (removalFromLaunchedQueriesLock.isHeldByCurrentThread()) {
                  removalFromLaunchedQueriesLock.unlock();
                }
              }
            } catch (LensException e) {

              log.error("Error launching query: {}", query.getQueryHandle(), e);
              String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
              setFailedStatus(query, "Launching query failed", reason, e.buildLensErrorTO(this.errorCollection));
              continue;

            } catch (Exception e) {
              log.error("Error launching query: {}", query.getQueryHandle(), e);
              String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
              setFailedStatus(query, "Launching query failed", reason, null);
              continue;
            } finally {
              release(query.getLensSessionIdentifier());
            }
          }
        } catch (InterruptedException e) {
          log.info("Query Submitter has been interrupted, exiting");
          return;
        } catch (Exception e) {
          incrCounter(QUERY_SUBMITTER_COUNTER);
          log.error("Error in query submitter", e);
        }
      }
      log.info("QuerySubmitter exited");
    }

    private void launchQuery(final QueryContext query) throws LensException {

      checkEstimatedQueriesState(query);
      QueryStatus oldStatus = query.getStatus();
      QueryStatus newStatus = new QueryStatus(query.getStatus().getProgress(), QueryStatus.Status.LAUNCHED,
          "Query is launched on driver", false, null, null, null);
      query.validateTransition(newStatus);

      // Check if we need to pass session's effective resources to selected driver
      addSessionResourcesToDriver(query);
      query.getSelectedDriver().executeAsync(query);
      query.setStatusSkippingTransitionTest(newStatus);
      query.setLaunchTime(System.currentTimeMillis());
      query.clearTransientStateAfterLaunch();

      launchedQueries.add(query);
      log.info("Added to launched queries. QueryId:{}", query.getQueryHandleString());
      fireStatusChangeEvent(query, newStatus, oldStatus);
    }

    private void addToWaitingQueries(final QueryContext query) throws LensException {

      checkEstimatedQueriesState(query);
      this.waitingQueries.add(query);
      log.info("Added to waiting queries. QueryId:{}", query.getQueryHandleString());
    }

    private void checkEstimatedQueriesState(final QueryContext query) throws LensException {
      if  (query.getSelectedDriver() == null || query.getSelectedDriverQueryCost() == null) {
        throw new LensException("selected driver: " + query.getSelectedDriver()  +" OR selected driver query cost: "
            + query.getSelectedDriverQueryCost() + " is null. Query doesn't appear to be an estimated query.");
      }
    }
  }

  // used in tests

  /**
   * Pause query submitter.
   */
  public void pauseQuerySubmitter() {
    querySubmitterRunnable.pausedForTest = true;
  }

  /**
   * The Class StatusPoller.
   */
  private class StatusPoller implements Runnable {

    /**
     * The poll interval.
     */
    long pollInterval = 1000;

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      log.info("Starting Status poller thread");
      while (!stopped && !statusPoller.isInterrupted()) {
        try {
          Set<QueryContext> launched = launchedQueries.getQueries();

          for (QueryContext ctx : launched) {
            if (stopped || statusPoller.isInterrupted()) {
              return;
            }

            logSegregationContext.setLogSegragationAndQueryId(ctx.getQueryHandleString());
            log.info("Polling status for {}", ctx.getQueryHandle());
            try {
              // session is not required to update status of the query
              // don't need to wrap this with acquire/release
              updateStatus(ctx.getQueryHandle());
            } catch (LensException e) {
              log.error("Error updating status ", e);
            }
          }
          Thread.sleep(pollInterval);
        } catch (InterruptedException e) {
          log.info("Status poller has been interrupted, exiting");
          return;
        } catch (Exception e) {
          incrCounter(STATUS_UPDATE_COUNTER);
          log.error("Error in status poller", e);
        }
      }
      log.info("StatusPoller exited");
    }
  }

  /**
   * Sets the failed status.
   *
   * @param ctx       the ctx
   * @param statusMsg the status msg
   * @param reason    the reason
   * @throws LensException the lens exception
   */
  void setFailedStatus(QueryContext ctx, String statusMsg, String reason, final LensErrorTO lensErrorTO)
    throws LensException {

    QueryStatus before = ctx.getStatus();
    ctx.setStatus(new QueryStatus(0.0f, FAILED, statusMsg, false, null, reason, lensErrorTO));
    updateFinishedQuery(ctx, before);
    fireStatusChangeEvent(ctx, ctx.getStatus(), before);
  }

  /**
   * Sets the cancelled status.
   *
   * @param ctx       the ctx
   * @param statusMsg the status msg
   * @throws LensException the lens exception
   */
  private void setCancelledStatus(QueryContext ctx, String statusMsg) throws LensException {
    QueryStatus before = ctx.getStatus();
    ctx.setStatus(new QueryStatus(0.0f, CANCELED, statusMsg, false, null, null, null));
    updateFinishedQuery(ctx, before);
    fireStatusChangeEvent(ctx, ctx.getStatus(), before);
  }

  /**
   * Update finished query.
   *
   * @param ctx    the ctx
   * @param before the before
   */
  private void updateFinishedQuery(QueryContext ctx, QueryStatus before) {
    // before would be null in case of server restart
    if (before != null) {
      if (before.queued()) {
        /* Seems like query is cancelled, remove it from both queuedQueries and waitingQueries because we don't know
        * where it is right now. It might happen that when we remove it from queued, it was in waiting OR
        * when we removed it from waiting, it was in queued. We might just miss removing it from everywhere due to this
        * hide and seek. Then QuerySubmitter thread will come to rescue, as it always checks that a query should be in
        * queued state before processing it after deque. If it is in cancelled state, then it will skip it. */
        queuedQueries.remove(ctx);
        waitingQueries.remove(ctx);
      } else {
        if (removeFromLaunchedQueries(ctx)) {
          processWaitingQueriesAsync(ctx);
        }
      }
    }
    finishedQueries.add(new FinishedQuery(ctx));
    ctx.clearTransientStateAfterLaunch();
  }

  void setSuccessState(QueryContext ctx) throws LensException {
    QueryStatus before = ctx.getStatus();
    ctx.setStatus(new QueryStatus(1.0f, SUCCESSFUL, "Query is successful!", ctx
      .isResultAvailableInDriver(), null, null, null));
    updateFinishedQuery(ctx, before);
    fireStatusChangeEvent(ctx, ctx.getStatus(), before);
  }

  /**
   * Update status.
   *
   * @param handle the handle
   * @throws LensException the lens exception
   */
  private void updateStatus(final QueryHandle handle) throws LensException {
    QueryContext ctx = allQueries.get(handle);
    if (ctx != null) {
      synchronized (ctx) {
        QueryStatus before = ctx.getStatus();
        if (!ctx.queued() && !ctx.finished() && !ctx.getDriverStatus().isFinished()) {
          log.info("Updating status for {}", ctx.getQueryHandle());
          try {
            ctx.getSelectedDriver().updateStatus(ctx);
            ctx.setStatus(ctx.getDriverStatus().toQueryStatus());
          } catch (LensException exc) {
            // Driver gave exception while updating status

            setFailedStatus(ctx, "Status update failed", exc.getMessage(), exc.buildLensErrorTO(this.errorCollection));
            log.error("Status update failed for {}", handle, exc);

          }
          // query is successfully executed by driver and
          // if query result need not be persisted or there is no result available in driver, move the query to
          // succeeded state immediately, otherwise result formatter will format the result and move it to succeeded
          if (ctx.getStatus().getStatus().equals(EXECUTED) && (!ctx.isPersistent()
            || !ctx.isResultAvailableInDriver())) {
            setSuccessState(ctx);
          } else {
            if (ctx.getStatus().finished()) {
              updateFinishedQuery(ctx, before);
            }
            fireStatusChangeEvent(ctx, ctx.getStatus(), before);
          }
        }
      }
    }
  }

  /**
   * New status change event.
   *
   * @param ctx       the ctx
   * @param prevState the prev state
   * @param currState the curr state
   * @return the status change
   */
  private static StatusChange newStatusChangeEvent(QueryContext ctx,
    QueryStatus.Status prevState, QueryStatus.Status currState) {
    QueryHandle query = ctx.getQueryHandle();
    switch (currState) {
    case CANCELED:
      // TODO: correct username. put who cancelled it, not the submitter. Similar for others
      return new QueryCancelled(ctx.getEndTime(), prevState, currState, query, ctx.getSubmittedUser(), null);
    case CLOSED:
      return new QueryClosed(ctx.getClosedTime(), prevState, currState, query, ctx.getSubmittedUser(), null);
    case FAILED:
      StringBuilder msgBuilder = new StringBuilder();
      msgBuilder.append(ctx.getStatus().getStatusMessage());
      if (!StringUtils.isBlank(ctx.getStatus().getErrorMessage())) {
        msgBuilder.append("\n Reason:\n");
        msgBuilder.append(ctx.getStatus().getErrorMessage());
      }
      return new QueryFailed(ctx.getEndTime(), prevState, currState, query, ctx.getSubmittedUser(),
        msgBuilder.toString());
    case LAUNCHED:
      return new QueryLaunched(ctx.getLaunchTime(), prevState, currState, query);
    case QUEUED:
      return new QueryQueued(ctx.getSubmissionTime(), prevState, currState, query, ctx.getSubmittedUser());
    case RUNNING:
      return new QueryRunning(System.currentTimeMillis() - ctx.getDriverStatus().getDriverStartTime(), prevState,
        currState, query);
    case EXECUTED:
      return new QueryExecuted(ctx.getDriverStatus().getDriverFinishTime(), prevState, currState, query);
    case SUCCESSFUL:
      return new QuerySuccess(ctx.getEndTime(), prevState, currState, query);
    default:
      log.warn("Query {} transitioned to {} state from {} state", query, currState, prevState);
      return null;
    }
  }

  /**
   * If query status has changed, fire a specific StatusChange event.
   *
   * @param ctx     the ctx
   * @param current the current
   * @param before  the before
   */
  private void fireStatusChangeEvent(QueryContext ctx, QueryStatus current, QueryStatus before) {
    if (ctx == null || current == null) {
      return;
    }

    QueryStatus.Status prevState = before.getStatus();
    QueryStatus.Status currentStatus = current.getStatus();
    if (currentStatus.equals(prevState)) {
      // No need to fire event since the state hasn't changed
      return;
    }

    StatusChange event = newStatusChangeEvent(ctx, prevState, currentStatus);
    if (event != null) {
      try {
        getEventService().notifyEvent(event);
      } catch (LensException e) {
        log.warn("LensEventService encountered error while handling event: {}", event.getEventId(), e);
      }
    }
  }

  /**
   * The Class QueryPurger.
   */
  private class QueryPurger implements Runnable {

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      log.info("Starting Query purger thread");
      while (!stopped && !queryPurger.isInterrupted()) {
        FinishedQuery finished = null;
        try {
          finished = finishedQueries.take();
          logSegregationContext.setLogSegragationAndQueryId(finished.getQueryHandleString());
        } catch (InterruptedException e) {
          log.info("QueryPurger has been interrupted, exiting");
          return;
        }
        try {
          FinishedLensQuery finishedQuery = new FinishedLensQuery(finished.getCtx());
          if (finished.ctx.getStatus().getStatus() == SUCCESSFUL) {
            if (finished.ctx.getStatus().isResultSetAvailable()) {
              LensResultSet set = getResultset(finished.getCtx().getQueryHandle());
              if (set != null && PersistentResultSet.class.isAssignableFrom(set.getClass())) {
                LensResultSetMetadata metadata = set.getMetadata();
                String outputPath = ((PersistentResultSet) set).getOutputPath();
                int rows = set.size();
                finishedQuery.setMetadataClass(metadata.getClass().getName());
                finishedQuery.setResult(outputPath);
                finishedQuery.setMetadata(MAPPER.writeValueAsString(metadata));
                finishedQuery.setRows(rows);
              }
            }
          }
          try {
            lensServerDao.insertFinishedQuery(finishedQuery);
            log.info("Saved query {} to DB", finishedQuery.getHandle());
          } catch (Exception e) {
            log.warn("Exception while purging query ", e);
            finishedQueries.add(finished);
            continue;
          }

          synchronized (finished.ctx) {
            finished.ctx.setFinishedQueryPersisted(true);
            try {
              if (finished.getCtx().getSelectedDriver() != null) {
                finished.getCtx().getSelectedDriver().closeQuery(finished.getCtx().getQueryHandle());
              }
            } catch (Exception e) {
              log.warn("Exception while closing query with selected driver.", e);
            }
            log.info("Purging: {}", finished.getCtx().getQueryHandle());
            allQueries.remove(finished.getCtx().getQueryHandle());
            resultSets.remove(finished.getCtx().getQueryHandle());
          }
          fireStatusChangeEvent(finished.getCtx(),
            new QueryStatus(1f, CLOSED, "Query purged", false, null, null, null), finished.getCtx().getStatus());
          log.info("Query purged: {}", finished.getCtx().getQueryHandle());

        } catch (LensException e) {
          incrCounter(QUERY_PURGER_COUNTER);
          log.error("Error closing  query ", e);
        } catch (Exception e) {
          incrCounter(QUERY_PURGER_COUNTER);
          log.error("Error in query purger", e);
        }
      }
      log.info("QueryPurger exited");
    }
  }

  /**
   * The Class PreparedQueryPurger.
   */
  private class PreparedQueryPurger implements Runnable {

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      log.info("Starting Prepared Query purger thread");
      while (!stopped && !prepareQueryPurger.isInterrupted()) {
        try {
          PreparedQueryContext prepared = preparedQueryQueue.take();
          logSegregationContext.setLogSegragationAndQueryId(prepared.getQueryHandleString());
          destroyPreparedQuery(prepared);
          log.info("Purged prepared query: {}", prepared.getPrepareHandle());
        } catch (LensException e) {
          incrCounter(PREPARED_QUERY_PURGER_COUNTER);
          log.error("Error closing prepared query ", e);
        } catch (InterruptedException e) {
          log.info("PreparedQueryPurger has been interrupted, exiting");
          return;
        } catch (Exception e) {
          incrCounter(PREPARED_QUERY_PURGER_COUNTER);
          log.error("Error in prepared query purger", e);
        }
      }
      log.info("PreparedQueryPurger exited");
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hive.service.CompositeService#init(org.apache.hadoop.hive.conf.HiveConf)
   */
  public synchronized void init(HiveConf hiveConf) {
    super.init(hiveConf);
    this.conf = hiveConf;

    this.launchedQueries
      = new ThreadSafeEstimatedQueryCollection(new DefaultEstimatedQueryCollection(new DefaultQueryCollection()));
    this.waitingQueries
      = new ThreadSafeEstimatedQueryCollection(new DefaultEstimatedQueryCollection(new DefaultQueryCollection()));

    ImmutableSet<QueryLaunchingConstraint> queryConstraints = getImplementations(
        QUERY_LAUNCHING_CONSTRAINT_FACTORIES_KEY, hiveConf);

    this.queryConstraintsChecker = new DefaultQueryLaunchingConstraintsChecker(queryConstraints);

    this.querySubmitterRunnable = new QuerySubmitter(LensServices.get().getErrorCollection(), this.waitingQueries,
        this.queryConstraintsChecker);
    this.querySubmitter = new Thread(querySubmitterRunnable, "QuerySubmitter");

    ImmutableSet<WaitingQueriesSelectionPolicy> selectionPolicies = getImplementations(
        WAITING_QUERIES_SELECTION_POLICY_FACTORIES_KEY, hiveConf);

    this.waitingQueriesSelector = new IntersectingWaitingQueriesSelector(selectionPolicies);

    try {
      this.userQueryToCubeQueryRewriter = new UserQueryToCubeQueryRewriter(conf);
    } catch (LensException e) {
      throw new IllegalStateException("Could not load phase 1 rewriters");
    }
    try {
      initializeQueryAcceptors();
    } catch (LensException e) {
      throw new IllegalStateException("Could not load acceptors");
    }
    initializeListeners();
    try {
      loadDriversAndSelector();
    } catch (LensException e) {
      log.error("Error while loading drivers", e);
      throw new IllegalStateException("Could not load drivers", e);
    }
    maxFinishedQueries = conf.getInt(MAX_NUMBER_OF_FINISHED_QUERY,
      DEFAULT_FINISHED_QUERIES);
    initalizeFinishedQueryStore(conf);
    log.info("Query execution service initialized");
  }

  /**
   * Initalize finished query store.
   *
   * @param conf the conf
   */
  private void initalizeFinishedQueryStore(Configuration conf) {
    this.lensServerDao = new LensServerDAO();
    this.lensServerDao.init(conf);
    try {
      this.lensServerDao.createFinishedQueriesTable();
    } catch (Exception e) {
      log.warn("Unable to create finished query table, query purger will not purge queries", e);
    }
    SimpleModule module = new SimpleModule("HiveColumnModule", new Version(1, 0, 0, null));
    module.addSerializer(ColumnDescriptor.class, new JsonSerializer<ColumnDescriptor>() {
      @Override
      public void serialize(ColumnDescriptor columnDescriptor, JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeStringField("name", columnDescriptor.getName());
        jsonGenerator.writeStringField("comment", columnDescriptor.getComment());
        jsonGenerator.writeNumberField("position", columnDescriptor.getOrdinalPosition());
        jsonGenerator.writeStringField("type", columnDescriptor.getType().getName());
        jsonGenerator.writeEndObject();
      }
    });
    module.addDeserializer(ColumnDescriptor.class, new JsonDeserializer<ColumnDescriptor>() {
      @Override
      public ColumnDescriptor deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
        throws IOException, JsonProcessingException {
        ObjectCodec oc = jsonParser.getCodec();
        JsonNode node = oc.readTree(jsonParser);
        org.apache.hive.service.cli.Type t = org.apache.hive.service.cli.Type.getType(node.get("type").asText());
        return new ColumnDescriptor(node.get("name").asText(), node.get("comment").asText(), new TypeDescriptor(t),
          node.get("position").asInt());
      }
    });
    MAPPER.registerModule(module);
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.LensService#prepareStopping()
   */
  public void prepareStopping() {
    super.prepareStopping();
    querySubmitter.interrupt();
    statusPoller.interrupt();
    queryPurger.interrupt();
    prepareQueryPurger.interrupt();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hive.service.CompositeService#stop()
   */
  public synchronized void stop() {
    super.stop();

    waitingQueriesSelectionSvc.shutdown();

    for (Thread th : new Thread[]{querySubmitter, statusPoller, queryPurger, prepareQueryPurger}) {
      try {
        log.debug("Waiting for {}", th.getName());
        th.join();
      } catch (InterruptedException e) {
        log.error("Error waiting for thread: {}", th.getName(), e);
      }
    }

    estimatePool.shutdownNow();
    log.info("Query execution service stopped");
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.hive.service.CompositeService#start()
   */
  public synchronized void start() {
    // recover query configurations from session
    synchronized (allQueries) {
      for (QueryContext ctx : allQueries.values()) {
        try {
          if (SESSION_MAP.containsKey(ctx.getLensSessionIdentifier())) {
            // try setting configuration if the query session is still not closed
            ctx.setConf(getLensConf(getSessionHandle(ctx.getLensSessionIdentifier()), ctx.getLensConf()));
          } else {
            ctx.setConf(getLensConf(ctx.getLensConf()));
          }
          for (LensDriver driver : drivers.values()) {
            if (ctx.getDriverContext() != null) {
              ctx.getDriverContext().setDriverConf(driver, ctx.getConf());
            }
          }
        } catch (LensException e) {
          log.error("Could not set query conf ", e);
        }
      }
    }
    super.start();
    querySubmitter.start();
    statusPoller.start();
    queryPurger.start();
    prepareQueryPurger.start();

    startEstimatePool();
  }

  private void startEstimatePool() {
    int minPoolSize = conf.getInt(ESTIMATE_POOL_MIN_THREADS,
      DEFAULT_ESTIMATE_POOL_MIN_THREADS);
    int maxPoolSize = conf.getInt(ESTIMATE_POOL_MAX_THREADS,
      DEFAULT_ESTIMATE_POOL_MAX_THREADS);
    int keepAlive = conf.getInt(ESTIMATE_POOL_KEEP_ALIVE_MILLIS,
      DEFAULT_ESTIMATE_POOL_KEEP_ALIVE_MILLIS);

    final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
    final AtomicInteger thId = new AtomicInteger();
    // We are creating our own thread factory, just so that we can override thread name for easy debugging
    ThreadFactory threadFactory = new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread th = defaultFactory.newThread(r);
        th.setName("estimate-" + thId.incrementAndGet());
        return th;
      }
    };

    log.debug("starting estimate pool");
    ThreadPoolExecutor estimatePool = new ThreadPoolExecutor(minPoolSize, maxPoolSize, keepAlive, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<Runnable>(), threadFactory);
    estimatePool.allowCoreThreadTimeOut(true);
    estimatePool.prestartCoreThread();
    this.estimatePool = estimatePool;
  }

  private static final String REWRITE_GAUGE = "CUBE_REWRITE";
  private static final String DRIVER_ESTIMATE_GAUGE = "DRIVER_ESTIMATE";
  private static final String DRIVER_SELECTOR_GAUGE = "DRIVER_SELECTION";
  private static final String PARALLEL_CALL_GAUGE = "PARALLEL_ESTIMATE";

  /**
   * Rewrite the query for each driver, and estimate query cost for the rewritten queries. Finally, select the driver
   * using driver selector.
   *
   * @param ctx query context
   * @throws LensException the lens exception
   */
  private void rewriteAndSelect(final AbstractQueryContext ctx) throws LensException {
    MethodMetricsContext parallelCallGauge = MethodMetricsFactory.createMethodGauge(ctx.getConf(), false,
        PARALLEL_CALL_GAUGE);
    try {
      userQueryToCubeQueryRewriter.rewrite(ctx);
      // Initially we obtain individual runnables for rewrite and estimate calls
      // These are mapped against the driver, so that later it becomes easy to chain them
      // for each driver.
      Map<LensDriver, RewriteUtil.DriverRewriterRunnable> rewriteRunnables = RewriteUtil.rewriteQuery(ctx);
      Map<LensDriver, AbstractQueryContext.DriverEstimateRunnable> estimateRunnables = ctx.getDriverEstimateRunnables();

      int numDrivers = ctx.getDriverContext().getDrivers().size();
      final CountDownLatch estimateCompletionLatch = new CountDownLatch(numDrivers);
      List<RewriteEstimateRunnable> runnables = new ArrayList<RewriteEstimateRunnable>(numDrivers);
      List<Future> estimateFutures = new ArrayList<Future>();

      for (final LensDriver driver : ctx.getDriverContext().getDrivers()) {
        RewriteEstimateRunnable r = new RewriteEstimateRunnable(driver,
          rewriteRunnables.get(driver),
          estimateRunnables.get(driver),
          ctx, estimateCompletionLatch);

        // Submit composite rewrite + estimate operation to background pool
        estimateFutures.add(estimatePool.submit(r));
        runnables.add(r);
      }

      // Wait for all rewrite and estimates to finish
      try {
        long estimateLatchTimeout = ctx.getConf().getLong(ESTIMATE_TIMEOUT_MILLIS,
          DEFAULT_ESTIMATE_TIMEOUT_MILLIS);
        boolean completed = estimateCompletionLatch.await(estimateLatchTimeout, TimeUnit.MILLISECONDS);

        // log operations yet to complete and  check if we can proceed with at least one driver
        if (!completed) {
          int inCompleteDrivers = 0;

          for (int i = 0; i < runnables.size(); i++) {
            RewriteEstimateRunnable r = runnables.get(i);
            if (!r.isCompleted()) {
              ++inCompleteDrivers;
              // Cancel the corresponding task
              estimateFutures.get(i).cancel(true);
              log.warn("Timeout reached for estimate task for driver {}" + r.getDriver());
            }
          }

          if (inCompleteDrivers == ctx.getDriverContext().getDrivers().size()) {
            throw new LensException("None of the drivers could complete within timeout: " + estimateLatchTimeout);
          }
        }
      } catch (InterruptedException exc) {
        throw new LensException("At least one of the estimate operation failed to complete in time", exc);
      }

      // Evaluate success of rewrite and estimate
      boolean succeededOnce = false;
      List<String> failureCauses = new ArrayList<String>(numDrivers);
      List<LensException> causes = new ArrayList<LensException>(numDrivers);

      for (RewriteEstimateRunnable r : runnables) {
        if (r.isSucceeded()) {
          succeededOnce = true;
        } else {
          failureCauses.add(r.getFailureCause());

          if (r.getCause() != null) {
            causes.add(r.getCause());
          }
        }
      }

      // Throw exception if none of the rewrite+estimates are successful.
      if (!succeededOnce) {
        if (!causes.isEmpty()) {
          final LensException firstCause = causes.get(0);
          for (LensException cause : causes) {
            if (!cause.equals(firstCause)) {
              throw new LensMultiCauseException(StringUtils.join(failureCauses, '\n'), ImmutableList.copyOf(causes));
            }
          }
          throw firstCause;
        } else {
          throw new LensException(StringUtils.join(failureCauses, '\n'));
        }
      }

      MethodMetricsContext selectGauge = MethodMetricsFactory.createMethodGauge(ctx.getConf(), false,
          DRIVER_SELECTOR_GAUGE);
      // 2. select driver to run the query
      LensDriver driver = driverSelector.select(ctx, conf);
      ctx.setSelectedDriver(driver);
      QueryCost selectedDriverQueryCost = ctx.getDriverContext().getDriverQueryCost(driver);
      ctx.setSelectedDriverQueryCost(selectedDriverQueryCost);
      selectGauge.markSuccess();
    } finally {
      parallelCallGauge.markSuccess();
    }
  }

  /**
   * Chains driver specific rewrite and estimate of the query in a single runnable, which can be processed in a
   * background thread
   */
  public class RewriteEstimateRunnable implements Runnable {
    @Getter
    private final LensDriver driver;
    private final RewriteUtil.DriverRewriterRunnable rewriterRunnable;
    private final AbstractQueryContext.DriverEstimateRunnable estimateRunnable;
    private final AbstractQueryContext ctx;
    private final CountDownLatch estimateCompletionLatch;

    @Getter
    private boolean succeeded;
    @Getter
    private String failureCause = null;

    @Getter
    private LensException cause;

    @Getter
    private volatile boolean completed;

    public RewriteEstimateRunnable(
      LensDriver driver,
      RewriteUtil.DriverRewriterRunnable rewriterRunnable,
      AbstractQueryContext.DriverEstimateRunnable estimateRunnable,
      AbstractQueryContext ctx,
      CountDownLatch estimateCompletionLatch) {
      this.driver = driver;
      this.rewriterRunnable = rewriterRunnable;
      this.estimateRunnable = estimateRunnable;
      this.ctx = ctx;
      this.estimateCompletionLatch = estimateCompletionLatch;
    }

    @Override
    public void run() {
      try {
        // With following set - explain estimate calls are setting queryLogId as requestid in logSegregationContext
        logSegregationContext.setLogSegragationAndQueryId(ctx.getLogHandle());
        acquire(ctx.getLensSessionIdentifier());
        MethodMetricsContext rewriteGauge = MethodMetricsFactory.createMethodGauge(ctx.getDriverConf(driver), true,
          REWRITE_GAUGE);
        // 1. Rewrite for driver
        rewriterRunnable.run();
        succeeded = rewriterRunnable.isSucceeded();
        if (!succeeded) {
          failureCause = rewriterRunnable.getFailureCause();
          cause = rewriterRunnable.getCause();
        }

        rewriteGauge.markSuccess();

        // 2. Estimate for driver only if rewrite succeeded.
        if (succeeded) {
          MethodMetricsContext estimateGauge = MethodMetricsFactory.createMethodGauge(ctx.getDriverConf(driver), true,
            DRIVER_ESTIMATE_GAUGE);

          estimateRunnable.run();
          succeeded = estimateRunnable.isSucceeded();

          if (!succeeded) {
            failureCause = estimateRunnable.getFailureCause();
            cause = estimateRunnable.getCause();
            log.error("Estimate failed for driver {} cause: {}", driver, failureCause);
          }
          estimateGauge.markSuccess();
        } else {
          log.error("Estimate skipped since rewrite failed for driver {} cause: {}", driver, failureCause);
        }
      } catch (Throwable th) {
        log.error("Error computing estimate for driver {}", driver, th);
      } finally {
        completed = true;
        try {
          release(ctx.getLensSessionIdentifier());
        } catch (LensException e) {
          log.error("Could not release session: {}", ctx.getLensSessionIdentifier(), e);
        } finally {
          estimateCompletionLatch.countDown();
        }
      }
    }
  }

  /**
   * Accept.
   *
   * @param query    the query
   * @param conf     the conf
   * @param submitOp the submit op
   * @throws LensException the lens exception
   */
  private void accept(String query, Configuration conf, SubmitOp submitOp) throws LensException {
    // run through all the query acceptors, and throw Exception if any of them
    // return false
    for (QueryAcceptor acceptor : queryAcceptors) {
      String cause = "";
      String rejectionCause = acceptor.accept(query, conf, submitOp);
      if (rejectionCause != null) {
        getEventService().notifyEvent(new QueryRejected(System.currentTimeMillis(), query, rejectionCause, null));
        throw new BadRequestException("Query not accepted because " + cause);
      }
    }
    getEventService().notifyEvent(new QueryAccepted(System.currentTimeMillis(), null, query, null));
  }

  /**
   * Gets the resultset from dao.
   *
   * @param queryHandle the query handle
   * @return the resultset from dao
   * @throws LensException the lens exception
   */
  private LensResultSet getResultsetFromDAO(QueryHandle queryHandle) throws LensException {
    FinishedLensQuery query = lensServerDao.getQuery(queryHandle.toString());
    if (query != null) {
      if (query.getResult() == null) {
        throw new NotFoundException("InMemory Query result purged " + queryHandle);
      }
      try {
        Class<LensResultSetMetadata> mdKlass = (Class<LensResultSetMetadata>) Class.forName(query.getMetadataClass());
        return new LensPersistentResult(MAPPER.readValue(query.getMetadata(), mdKlass), query.getResult(),
          query.getRows());
      } catch (Exception e) {
        throw new LensException(e);
      }
    }
    throw new NotFoundException("Query not found: " + queryHandle);
  }

  /**
   * Gets the resultset.
   *
   * @param queryHandle the query handle
   * @return the resultset
   * @throws LensException the lens exception
   */
  private LensResultSet getResultset(QueryHandle queryHandle) throws LensException {
    QueryContext ctx = allQueries.get(queryHandle);
    if (ctx == null) {
      return getResultsetFromDAO(queryHandle);
    } else {
      synchronized (ctx) {
        if (ctx.isFinishedQueryPersisted()) {
          return getResultsetFromDAO(queryHandle);
        }
        LensResultSet resultSet = resultSets.get(queryHandle);
        if (resultSet == null) {
          if (ctx.isPersistent() && ctx.getQueryOutputFormatter() != null) {
            resultSets
              .put(queryHandle,
                new LensPersistentResult(
                  ctx.getQueryOutputFormatter().getMetadata(),
                  ctx.getQueryOutputFormatter().getFinalOutputPath(),
                  ctx.getQueryOutputFormatter().getNumRows()));
          } else if (allQueries.get(queryHandle).isResultAvailableInDriver()) {
            resultSet = allQueries.get(queryHandle).getSelectedDriver().fetchResultSet(allQueries.get(queryHandle));
            resultSets.put(queryHandle, resultSet);
          } else {
            throw new NotFoundException("Result set not available for query:" + queryHandle);
          }
        }
      }
      if (resultSets.get(queryHandle) instanceof InMemoryResultSet) {
        ((InMemoryResultSet) resultSets.get(queryHandle)).seekToStart();
      }
      return resultSets.get(queryHandle);
    }
  }

  /**
   * Gets the driver resultset.
   *
   * @param queryHandle the query handle
   * @return the driver resultset
   * @throws LensException the lens exception
   */
  LensResultSet getDriverResultset(QueryHandle queryHandle) throws LensException {
    return allQueries.get(queryHandle).getSelectedDriver().fetchResultSet(allQueries.get(queryHandle));
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#prepare(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryPrepareHandle prepare(LensSessionHandle sessionHandle, String query, LensConf lensConf, String queryName)
    throws LensException {
    PreparedQueryContext prepared = null;
    try {
      acquire(sessionHandle);
      prepared = prepareQuery(sessionHandle, query, lensConf, SubmitOp.PREPARE);
      prepared.setQueryName(queryName);
      prepared.getSelectedDriver().prepare(prepared);
      return prepared.getPrepareHandle();
    } catch (LensException e) {
      if (prepared != null) {
        destroyPreparedQuery(prepared);
      }
      throw e;
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Prepare query.
   *
   * @param sessionHandle the session handle
   * @param query         the query
   * @param lensConf      the lens conf
   * @param op            the op
   * @return the prepared query context
   * @throws LensException the lens exception
   */
  private PreparedQueryContext prepareQuery(LensSessionHandle sessionHandle, String query, LensConf lensConf,
    SubmitOp op) throws LensException {
    Configuration conf = getLensConf(sessionHandle, lensConf);
    accept(query, conf, op);
    PreparedQueryContext prepared = new PreparedQueryContext(query, getSession(sessionHandle).getLoggedInUser(), conf,
      lensConf, drivers.values());
    prepared.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
    rewriteAndSelect(prepared);
    preparedQueries.put(prepared.getPrepareHandle(), prepared);
    preparedQueryQueue.add(prepared);
    incrCounter(PREPARED_QUERIES_COUNTER);
    return prepared;
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.lens.server.api.query.QueryExecutionService#explainAndPrepare(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryPlan explainAndPrepare(LensSessionHandle sessionHandle, String query, LensConf lensConf, String queryName)
    throws LensException {
    PreparedQueryContext prepared = null;
    try {
      log.info("ExplainAndPrepare: session:{} query: {}", sessionHandle, query);
      acquire(sessionHandle);
      prepared = prepareQuery(sessionHandle, query, lensConf, SubmitOp.EXPLAIN_AND_PREPARE);
      prepared.setQueryName(queryName);
      addSessionResourcesToDriver(prepared);
      QueryPlan plan = prepared.getSelectedDriver().explainAndPrepare(prepared).toQueryPlan();
      plan.setPrepareHandle(prepared.getPrepareHandle());
      return plan;
    } catch (LensException e) {
      if (prepared != null) {
        destroyPreparedQuery(prepared);
      }
      log.error("Explain and prepare failed", e);
      QueryPlan plan;
      if (e.getCause() != null && e.getCause().getMessage() != null) {
        plan = new QueryPlan(true, e.getCause().getMessage());
      } else {
        plan = new QueryPlan(true, e.getMessage());
      }
      return plan;
    } catch (UnsupportedEncodingException e) {
      throw new LensException(e);
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.lens.server.api.query.QueryExecutionService#executePrepareAsync(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryPrepareHandle, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryHandle executePrepareAsync(LensSessionHandle sessionHandle, QueryPrepareHandle prepareHandle,
    LensConf conf, String queryName) throws LensException {
    try {
      log.info("ExecutePrepareAsync: session:{} prepareHandle:{}", sessionHandle,  prepareHandle.getPrepareHandleId());
      acquire(sessionHandle);
      PreparedQueryContext pctx = getPreparedQueryContext(sessionHandle, prepareHandle);
      Configuration qconf = getLensConf(sessionHandle, conf);
      accept(pctx.getUserQuery(), qconf, SubmitOp.EXECUTE);
      QueryContext ctx = createContext(pctx, getSession(sessionHandle).getLoggedInUser(), conf, qconf);
      if (StringUtils.isNotBlank(queryName)) {
        // Override previously set query name
        ctx.setQueryName(queryName);
      } else {
        ctx.setQueryName(pctx.getQueryName());
      }
      ctx.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
      return submitQuery(ctx);
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#executePrepare(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryPrepareHandle, long, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryHandleWithResultSet executePrepare(LensSessionHandle sessionHandle, QueryPrepareHandle prepareHandle,
    long timeoutMillis, LensConf conf, String queryName) throws LensException {
    try {
      log.info("ExecutePrepare: session:{} prepareHandle: {} timeout:{}", sessionHandle,
        prepareHandle.getPrepareHandleId(), timeoutMillis);
      acquire(sessionHandle);
      PreparedQueryContext pctx = getPreparedQueryContext(sessionHandle, prepareHandle);
      Configuration qconf = getLensConf(sessionHandle, conf);
      QueryContext ctx = createContext(pctx, getSession(sessionHandle).getLoggedInUser(), conf, qconf);
      if (StringUtils.isNotBlank(queryName)) {
        // Override previously set query name
        ctx.setQueryName(queryName);
      } else {
        ctx.setQueryName(pctx.getQueryName());
      }
      return executeTimeoutInternal(sessionHandle, ctx, timeoutMillis, qconf);
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#executeAsync(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryHandle executeAsync(LensSessionHandle sessionHandle, String query, LensConf conf, String queryName)
    throws LensException {
    try {
      log.info("ExecuteAsync: session:{} query: {}", sessionHandle, query);
      acquire(sessionHandle);
      Configuration qconf = getLensConf(sessionHandle, conf);
      accept(query, qconf, SubmitOp.EXECUTE);
      QueryContext ctx = createContext(query, getSession(sessionHandle).getLoggedInUser(), conf, qconf);
      ctx.setQueryName(queryName);
      return executeAsyncInternal(sessionHandle, ctx);
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Creates the context.
   *
   * @param query    the query
   * @param userName the user name
   * @param conf     the conf
   * @param qconf    the qconf
   * @return the query context
   * @throws LensException the lens exception
   */
  protected QueryContext createContext(String query, String userName, LensConf conf, Configuration qconf)
    throws LensException {
    QueryContext ctx = new QueryContext(query, userName, conf, qconf, drivers.values());
    return ctx;
  }

  /**
   * Creates the context.
   *
   * @param pctx     the pctx
   * @param userName the user name
   * @param conf     the conf
   * @param qconf    the qconf
   * @return the query context
   * @throws LensException the lens exception
   */
  protected QueryContext createContext(PreparedQueryContext pctx, String userName, LensConf conf, Configuration qconf)
    throws LensException {
    QueryContext ctx = new QueryContext(pctx, userName, conf, qconf);
    return ctx;
  }

  /**
   * Execute async internal.
   *
   * @param sessionHandle the session handle
   * @param ctx           the ctx
   * @return the query handle
   * @throws LensException the lens exception
   */
  private QueryHandle executeAsyncInternal(LensSessionHandle sessionHandle, QueryContext ctx) throws LensException {

    ctx.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
    rewriteAndSelect(ctx);
    return submitQuery(ctx);
  }

  private QueryHandle submitQuery(final QueryContext ctx) throws LensException {

    QueryStatus before = ctx.getStatus();
    ctx.setStatus(new QueryStatus(0.0, QUEUED, "Query is queued", false, null, null, null));
    queuedQueries.add(ctx);
    log.debug("Added to Queued Queries:{}", ctx.getQueryHandleString());
    allQueries.put(ctx.getQueryHandle(), ctx);
    fireStatusChangeEvent(ctx, ctx.getStatus(), before);
    log.info("Returning handle {}", ctx.getQueryHandle().getHandleId());
    return ctx.getQueryHandle();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#updateQueryConf(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle, org.apache.lens.api.LensConf)
   */
  @Override
  public boolean updateQueryConf(LensSessionHandle sessionHandle, QueryHandle queryHandle, LensConf newconf)
    throws LensException {
    try {
      log.info("UpdateQueryConf: session:{} queryHandle: {}", sessionHandle, queryHandle);
      acquire(sessionHandle);
      QueryContext ctx = getQueryContext(sessionHandle, queryHandle);
      if (ctx != null && (ctx.queued())) {
        ctx.updateConf(newconf.getProperties());
        // TODO COnf changed event tobe raised
        return true;
      } else {
        return false;
      }
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#updateQueryConf(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryPrepareHandle, org.apache.lens.api.LensConf)
   */
  @Override
  public boolean updateQueryConf(LensSessionHandle sessionHandle, QueryPrepareHandle prepareHandle, LensConf newconf)
    throws LensException {
    try {
      log.info("UpdatePreparedQueryConf: session:{} prepareHandle:{}", sessionHandle, prepareHandle);
      acquire(sessionHandle);
      PreparedQueryContext ctx = getPreparedQueryContext(sessionHandle, prepareHandle);
      ctx.updateConf(newconf.getProperties());
      return true;
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Gets the query context.
   *
   * @param sessionHandle the session handle
   * @param queryHandle   the query handle
   * @return the query context
   * @throws LensException the lens exception
   */
  private QueryContext getQueryContext(LensSessionHandle sessionHandle, QueryHandle queryHandle) throws LensException {
    try {
      acquire(sessionHandle);
      QueryContext ctx = allQueries.get(queryHandle);
      if (ctx == null) {
        FinishedLensQuery query = lensServerDao.getQuery(queryHandle.toString());
        log.info("FinishedLensQuery:{}", query);
        if (query == null) {
          throw new NotFoundException("Query not found " + queryHandle);
        }
        // pass the query conf instead of service conf
        return query.toQueryContext(conf, drivers.values());
      }
      updateStatus(queryHandle);
      return ctx;
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Gets the query context.
   *
   * @param queryHandle the query handle
   * @return the query context
   */
  QueryContext getQueryContext(QueryHandle queryHandle) {
    return allQueries.get(queryHandle);
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#getQuery(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle)
   */
  @Override
  public LensQuery getQuery(LensSessionHandle sessionHandle, QueryHandle queryHandle) throws LensException {
    return getQueryContext(sessionHandle, queryHandle).toLensQuery();
  }

  /**
   * Gets the prepared query context.
   *
   * @param sessionHandle the session handle
   * @param prepareHandle the prepare handle
   * @return the prepared query context
   * @throws LensException the lens exception
   */
  private PreparedQueryContext getPreparedQueryContext(LensSessionHandle sessionHandle,
    QueryPrepareHandle prepareHandle)
    throws LensException {
    try {
      acquire(sessionHandle);
      PreparedQueryContext ctx = preparedQueries.get(prepareHandle);
      if (ctx == null) {
        throw new NotFoundException("Prepared query not found " + prepareHandle);
      }
      return ctx;
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#getPreparedQuery(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryPrepareHandle)
   */
  @Override
  public LensPreparedQuery getPreparedQuery(LensSessionHandle sessionHandle, QueryPrepareHandle prepareHandle)
    throws LensException {
    return getPreparedQueryContext(sessionHandle, prepareHandle).toPreparedQuery();
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#execute(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, long, org.apache.lens.api.LensConf, java.lang.String)
   */
  @Override
  public QueryHandleWithResultSet execute(LensSessionHandle sessionHandle, String query, long timeoutMillis,
    LensConf conf, String queryName) throws LensException {
    try {
      log.info("Blocking execute session:{} query: {} timeout: {}", sessionHandle, query, timeoutMillis);
      acquire(sessionHandle);
      Configuration qconf = getLensConf(sessionHandle, conf);
      accept(query, qconf, SubmitOp.EXECUTE);
      QueryContext ctx = createContext(query, getSession(sessionHandle).getLoggedInUser(), conf, qconf);
      ctx.setQueryName(queryName);
      ctx.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
      rewriteAndSelect(ctx);
      return executeTimeoutInternal(sessionHandle, ctx, timeoutMillis, qconf);
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Execute timeout internal.
   *
   * @param sessionHandle the session handle
   * @param ctx           the ctx
   * @param timeoutMillis the timeout millis
   * @param conf          the conf
   * @return the query handle with result set
   * @throws LensException the lens exception
   */
  private QueryHandleWithResultSet executeTimeoutInternal(LensSessionHandle sessionHandle, QueryContext ctx,
    long timeoutMillis, Configuration conf) throws LensException {
    QueryHandle handle = submitQuery(ctx);
    QueryHandleWithResultSet result = new QueryHandleWithResultSet(handle);
    // getQueryContext calls updateStatus, which fires query events if there's a change in status

    while (isQueued(sessionHandle, handle)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        log.error("Encountered Interrupted exception.", e);
      }
    }
    QueryCompletionListener listener = new QueryCompletionListenerImpl(handle);
    if (getQueryContext(sessionHandle, handle).getSelectedDriver() == null) {
      result.setStatus(getQueryContext(sessionHandle, handle).getStatus());
      return result;
    }
    synchronized (ctx) {
      if (!ctx.getStatus().finished()) {
        getQueryContext(sessionHandle, handle).getSelectedDriver()
          .registerForCompletionNotification(handle, timeoutMillis, listener);
        try {
          synchronized (listener) {
            listener.wait(timeoutMillis);
          }
        } catch (InterruptedException e) {
          log.info("Waiting thread interrupted");
        }
      }
    }

    if (getQueryContext(sessionHandle, handle).getStatus().finished()) {
      if (getQueryContext(sessionHandle, handle).getStatus().isResultSetAvailable()) {
        result.setResult(getResultset(handle).toQueryResult());
      }
    }

    result.setStatus(getQueryContext(sessionHandle, handle).getStatus());

    return result;
  }

  private boolean isQueued(final LensSessionHandle sessionHandle, final QueryHandle handle)
    throws LensException {
    // getQueryContext calls updateStatus, which fires query events if there's a change in status
    QueryContext query = getQueryContext(sessionHandle, handle);
    synchronized (query) {
      return query.queued();
    }
  }

  /**
   * The Class QueryCompletionListenerImpl.
   */
  class QueryCompletionListenerImpl implements QueryCompletionListener {

    /**
     * The succeeded.
     */
    boolean succeeded = false;

    /**
     * The handle.
     */
    QueryHandle handle;

    /**
     * Instantiates a new query completion listener impl.
     *
     * @param handle the handle
     */
    QueryCompletionListenerImpl(QueryHandle handle) {
      this.handle = handle;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.lens.server.api.driver.QueryCompletionListener#onCompletion(org.apache.lens.api.query.QueryHandle)
     */
    @Override
    public void onCompletion(QueryHandle handle) {
      synchronized (this) {
        succeeded = true;
        log.info("Query {} with time out succeeded", handle);
        this.notify();
      }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.lens.server.api.driver.QueryCompletionListener#onError(org.apache.lens.api.query.QueryHandle,
     * java.lang.String)
     */
    @Override
    public void onError(QueryHandle handle, String error) {
      synchronized (this) {
        succeeded = false;
        log.info("Query {} with time out failed", handle);
        this.notify();
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.lens.server.api.query.QueryExecutionService#getResultSetMetadata(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle)
   */
  @Override
  public QueryResultSetMetadata getResultSetMetadata(LensSessionHandle sessionHandle, QueryHandle queryHandle)
    throws LensException {
    try {
      log.info("GetResultSetMetadata: session:{} query: {}", sessionHandle,  queryHandle);
      acquire(sessionHandle);
      LensResultSet resultSet = getResultset(queryHandle);
      if (resultSet != null) {
        return resultSet.getMetadata().toQueryResultSetMetadata();
      } else {
        throw new NotFoundException("Resultset metadata not found for query: (" + sessionHandle + ", " + queryHandle
          + ")");
      }
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#fetchResultSet(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle, long, int)
   */
  @Override
  public QueryResult fetchResultSet(LensSessionHandle sessionHandle, QueryHandle queryHandle, long startIndex,
    int fetchSize) throws LensException {
    try {
      log.info("FetchResultSet: session:{} query:{}", sessionHandle, queryHandle);
      acquire(sessionHandle);
      return getResultset(queryHandle).toQueryResult();
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#closeResultSet(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle)
   */
  @Override
  public void closeResultSet(LensSessionHandle sessionHandle, QueryHandle queryHandle) throws LensException {
    try {
      log.info("CloseResultSet:session:{} query:{}", sessionHandle, queryHandle);
      acquire(sessionHandle);
      resultSets.remove(queryHandle);
      // Ask driver to close result set
      getQueryContext(queryHandle).getSelectedDriver().closeResultSet(queryHandle);
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#cancelQuery(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle)
   */
  @Override
  public boolean cancelQuery(LensSessionHandle sessionHandle, QueryHandle queryHandle) throws LensException {
    try {
      log.info("CancelQuery: session:{} query:{}", sessionHandle, queryHandle);
      acquire(sessionHandle);
      QueryContext ctx = getQueryContext(sessionHandle, queryHandle);

      synchronized (ctx) {

        if (ctx.finished()) {
          return false;
        }

        if (ctx.launched() || ctx.running()) {
          boolean ret = ctx.getSelectedDriver().cancelQuery(queryHandle);
          if (!ret) {
            return false;
          }
        }

        setCancelledStatus(ctx, "Query is cancelled");
        return true;
      }
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#getAllQueries(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, java.lang.String, java.lang.String, long, long)
   */
  @Override
  public List<QueryHandle> getAllQueries(LensSessionHandle sessionHandle, String state, String userName,
    String queryName, long fromDate, long toDate) throws LensException {
    validateTimeRange(fromDate, toDate);
    userName = UtilityMethods.removeDomain(userName);
    try {
      acquire(sessionHandle);
      Status status = null;
      try {
        status = StringUtils.isBlank(state) ? null : Status.valueOf(state);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Bad state argument passed, possible values are " + Status.values(), e);
      }
      boolean filterByStatus = status != null;
      queryName = queryName.toLowerCase();
      boolean filterByQueryName = StringUtils.isNotBlank(queryName);

      if (StringUtils.isBlank(userName)) {
        userName = getSession(sessionHandle).getLoggedInUser();
      }

      List<QueryHandle> all = new ArrayList<QueryHandle>(allQueries.keySet());
      Iterator<QueryHandle> itr = all.iterator();
      while (itr.hasNext()) {
        QueryHandle q = itr.next();
        QueryContext context = allQueries.get(q);
        long querySubmitTime = context.getSubmissionTime();
        if ((filterByStatus && status != context.getStatus().getStatus())
          || (filterByQueryName && !context.getQueryName().toLowerCase().contains(queryName))
          || (!"all".equalsIgnoreCase(userName) && !userName.equalsIgnoreCase(context.getSubmittedUser()))
          || (!(fromDate <= querySubmitTime && querySubmitTime <= toDate))) {
          itr.remove();
        }
      }

      // Unless user wants to get queries in 'non finished' state, get finished queries from DB as well
      if (status == null || status == CANCELED || status == SUCCESSFUL || status == FAILED) {
        if ("all".equalsIgnoreCase(userName)) {
          userName = null;
        }
        List<QueryHandle> persistedQueries = lensServerDao.findFinishedQueries(state, userName, queryName, fromDate,
          toDate);
        if (persistedQueries != null && !persistedQueries.isEmpty()) {
          log.info("Adding persisted queries {}", persistedQueries.size());
          all.addAll(persistedQueries);
        }
      }

      return all;
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.apache.lens.server.api.query.QueryExecutionService#getAllPreparedQueries(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, java.lang.String, long, long)
   */
  @Override
  public List<QueryPrepareHandle> getAllPreparedQueries(LensSessionHandle sessionHandle, String user, String queryName,
    long fromDate, long toDate) throws LensException {
    validateTimeRange(fromDate, toDate);
    user = UtilityMethods.removeDomain(user);
    try {
      acquire(sessionHandle);
      List<QueryPrepareHandle> allPrepared = new ArrayList<QueryPrepareHandle>(preparedQueries.keySet());
      Iterator<QueryPrepareHandle> itr = allPrepared.iterator();
      while (itr.hasNext()) {
        QueryPrepareHandle q = itr.next();
        PreparedQueryContext preparedQueryContext = preparedQueries.get(q);

        if (StringUtils.isNotBlank(user)) {
          if ("all".equalsIgnoreCase(user)) {
            continue;
          } else if (user.equalsIgnoreCase(preparedQueryContext.getPreparedUser())) {
            continue;
          }
        }

        if (StringUtils.isNotBlank(queryName)) {
          if (preparedQueryContext.getQueryName().toLowerCase().contains(queryName.toLowerCase())) {
            continue;
          }
        }
        long queryPrepTime = preparedQueryContext.getPreparedTime().getTime();
        if (fromDate <= queryPrepTime && queryPrepTime <= toDate) {
          continue;
        }
        itr.remove();
      }
      return allPrepared;
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Validate time range.
   *
   * @param fromDate the from date
   * @param toDate   the to date
   */
  private void validateTimeRange(long fromDate, long toDate) {
    if (fromDate >= toDate) {
      throw new BadRequestException("Invalid time range: [" + fromDate + ", " + toDate + "]");
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#destroyPrepared(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryPrepareHandle)
   */
  @Override
  public boolean destroyPrepared(LensSessionHandle sessionHandle, QueryPrepareHandle prepared) throws LensException {
    try {
      log.info("DestroyPrepared: {} prepareHandle:{}", sessionHandle, prepared);
      acquire(sessionHandle);
      destroyPreparedQuery(getPreparedQueryContext(sessionHandle, prepared));
      return true;
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Destroy prepared query.
   *
   * @param ctx the ctx
   * @throws LensException the lens exception
   */
  private void destroyPreparedQuery(PreparedQueryContext ctx) throws LensException {
    if (ctx.getSelectedDriver() != null) {
      ctx.getSelectedDriver().closePreparedQuery(ctx.getPrepareHandle());
    }
    preparedQueries.remove(ctx.getPrepareHandle());
    preparedQueryQueue.remove(ctx);
    decrCounter(PREPARED_QUERIES_COUNTER);
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#estimate(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, org.apache.lens.api.LensConf)
   */
  @Override
  public QueryCost estimate(final String requestId, LensSessionHandle sessionHandle, String query, LensConf lensConf)
    throws LensException {
    try {
      log.info("Estimate: session :{} query:{}", sessionHandle, query);
      acquire(sessionHandle);
      Configuration qconf = getLensConf(sessionHandle, lensConf);
      ExplainQueryContext estimateQueryContext = new ExplainQueryContext(requestId, query,
        getSession(sessionHandle).getLoggedInUser(), lensConf, qconf, drivers.values());
      estimateQueryContext.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
      accept(query, qconf, SubmitOp.ESTIMATE);
      rewriteAndSelect(estimateQueryContext);
      return estimateQueryContext.getSelectedDriverQueryCost();
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#explain(org.apache.lens.api.LensSessionHandle,
   * java.lang.String, org.apache.lens.api.LensConf)
   */
  @Override
  public QueryPlan explain(final String requestId, LensSessionHandle sessionHandle, String query, LensConf lensConf)
    throws LensException {
    try {
      log.info("Explain: session:{} query:{}",  sessionHandle, query);
      acquire(sessionHandle);
      Configuration qconf = getLensConf(sessionHandle, lensConf);
      ExplainQueryContext explainQueryContext = new ExplainQueryContext(requestId, query,
        getSession(sessionHandle).getLoggedInUser(), lensConf, qconf, drivers.values());
      explainQueryContext.setLensSessionIdentifier(sessionHandle.getPublicId().toString());
      accept(query, qconf, SubmitOp.EXPLAIN);
      rewriteAndSelect(explainQueryContext);
      addSessionResourcesToDriver(explainQueryContext);
      return explainQueryContext.getSelectedDriver().explain(explainQueryContext).toQueryPlan();
    } catch (LensException e) {
      log.error("Error during explain :", e);
      QueryPlan plan;
      if (e.getCause() != null && e.getCause().getMessage() != null) {
        plan = new QueryPlan(true, e.getCause().getMessage());
      } else {
        plan = new QueryPlan(true, e.getMessage());
      }
      return plan;
    } catch (UnsupportedEncodingException e) {
      throw new LensException(e);
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Creates the add/delete resource query.
   *
   * @param command
   * @param sessionHandle
   * @param driver
   * @return
   * @throws LensException
   */
  private QueryContext createResourceQuery(String command, LensSessionHandle sessionHandle, LensDriver driver)
    throws LensException {
    LensConf qconf = new LensConf();
    qconf.addProperty(QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    QueryContext addQuery = QueryContext.createContextWithSingleDriver(command,
      getSession(sessionHandle).getLoggedInUser(), qconf, getLensConf(
        sessionHandle, qconf), driver, sessionHandle.getPublicId().toString(), true);
    return addQuery;
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.LensService#deleteResource(org.apache.lens.api.LensSessionHandle, java.lang.String,
   * java.lang.String)
   */
  public void deleteResource(LensSessionHandle sessionHandle, String type, String path) throws LensException {
    try {
      acquire(sessionHandle);
      String command = "delete " + type.toLowerCase() + " " + path;
      for (LensDriver driver : drivers.values()) {
        if (driver instanceof HiveDriver) {
          driver.execute(createResourceQuery(command, sessionHandle, driver));
        }
      }
    } finally {
      release(sessionHandle);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.LensService#readExternal(java.io.ObjectInput)
   */
  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    super.readExternal(in);
    // Restore drivers
    synchronized (drivers) {
      int numDrivers = in.readInt();
      for (int i = 0; i < numDrivers; i++) {
        String driverClsName = in.readUTF();
        LensDriver driver = drivers.get(driverClsName);
        if (driver == null) {
          // this driver is removed in the current server restart
          // we will create an instance and read its state still.
          try {
            Class<? extends LensDriver> driverCls = (Class<? extends LensDriver>) Class.forName(driverClsName);
            driver = (LensDriver) driverCls.newInstance();
            driver.configure(conf);
          } catch (Exception e) {
            log.error("Could not instantiate driver:{}", driverClsName, e);
            throw new IOException(e);
          }
          log.info("Driver state for {} will be ignored", driverClsName);
        }
        driver.readExternal(in);
      }
    }

    // Restore queries
    synchronized (allQueries) {
      int numQueries = in.readInt();

      for (int i = 0; i < numQueries; i++) {
        QueryContext ctx = (QueryContext) in.readObject();
        ctx.initTransientState();

        //Create DriverSelectorQueryContext by passing all the drivers and the user query
        //Driver conf gets reset in start
        DriverSelectorQueryContext driverCtx = new DriverSelectorQueryContext(ctx.getUserQuery(), new Configuration(),
          drivers.values());
        ctx.setDriverContext(driverCtx);
        boolean driverAvailable = in.readBoolean();
        // set the selected driver if available, if not available for the cases of queued queries,
        // query service will do the selection from existing drivers and update
        if (driverAvailable) {
          String clsName = in.readUTF();
          ctx.getDriverContext().setSelectedDriver(drivers.get(clsName));
        }
        allQueries.put(ctx.getQueryHandle(), ctx);
      }

      // populate the query queues
      final List<QueryContext> allRestoredQueuedQueries = new LinkedList<QueryContext>();
      for (QueryContext ctx : allQueries.values()) {
        switch (ctx.getStatus().getStatus()) {
        case NEW:
        case QUEUED:
          allRestoredQueuedQueries.add(ctx);
          break;
        case LAUNCHED:
        case RUNNING:
          try {
            launchedQueries.add(ctx);
          } catch (final Exception e) {
            log.error("Query not restored:QueryContext:{}", ctx, e);
          }
          break;
        case SUCCESSFUL:
        case FAILED:
        case CANCELED:
          updateFinishedQuery(ctx, null);
          break;
        case CLOSED:
          allQueries.remove(ctx.getQueryHandle());
        }
      }
      queuedQueries.addAll(allRestoredQueuedQueries);
      log.info("Recovered {} queries", allQueries.size());
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.LensService#writeExternal(java.io.ObjectOutput)
   */
  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    super.writeExternal(out);
    // persist all drivers
    synchronized (drivers) {
      out.writeInt(drivers.size());
      for (LensDriver driver : drivers.values()) {
        synchronized (driver) {
          out.writeUTF(driver.getClass().getName());
          driver.writeExternal(out);
        }
      }
    }
    // persist allQueries
    synchronized (allQueries) {
      out.writeInt(allQueries.size());
      for (QueryContext ctx : allQueries.values()) {
        synchronized (ctx) {
          out.writeObject(ctx);
          boolean isDriverAvailable = (ctx.getSelectedDriver() != null);
          out.writeBoolean(isDriverAvailable);
          if (isDriverAvailable) {
            out.writeUTF(ctx.getSelectedDriver().getClass().getName());
          }
        }
      }
      log.info("Persisted {} queries", allQueries.size());
    }
  }

  /**
   * @inheritDoc
   */
  @Override
  public HealthStatus getHealthStatus() {
    boolean isHealthy = true;
    StringBuilder details = new StringBuilder();

    if (!this.getServiceState().equals(STATE.STARTED)) {
      isHealthy = false;
      details.append("Query execution service is down.");
    }

    if (!this.statusPoller.isAlive()) {
      isHealthy = false;
      details.append("Status poller thread is dead.");
    }

    if (!this.prepareQueryPurger.isAlive()) {
      isHealthy = false;
      details.append("PrepareQuery purger thread is dead.");
    }

    if (!this.queryPurger.isAlive()) {
      isHealthy = false;
      details.append("Query purger thread is dead.");
    }

    if (!this.querySubmitter.isAlive()) {
      isHealthy = false;
      details.append("Query submitter thread is dead.");
    }

    if (this.estimatePool.isShutdown() || this.estimatePool.isTerminated()) {
      isHealthy = false;
      details.append("Estimate Pool is dead.");
    }

    if (querySubmitterRunnable.pausedForTest) {
      isHealthy = false;
      details.append("QuerySubmitter paused for test.");
    }

    if (!isHealthy) {
      log.error(details.toString());
    }

    return isHealthy
        ? new HealthStatus(isHealthy, "QueryExecution service is healthy.")
        : new HealthStatus(isHealthy, details.toString());
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.api.query.QueryExecutionService#getHttpResultSet(org.apache.lens.api.LensSessionHandle,
   * org.apache.lens.api.query.QueryHandle)
   */
  @Override
  public Response getHttpResultSet(LensSessionHandle sessionHandle, QueryHandle queryHandle) throws LensException {
    final QueryContext ctx = getQueryContext(sessionHandle, queryHandle);
    LensResultSet result = getResultset(queryHandle);
    if (result instanceof LensPersistentResult) {
      final Path resultPath = new Path(((PersistentResultSet) result).getOutputPath());
      try {
        FileSystem fs = resultPath.getFileSystem(conf);
        if (fs.isDirectory(resultPath)) {
          throw new NotFoundException("Http result not available for query:" + queryHandle.toString());
        }
      } catch (IOException e) {
        log.warn("Unable to get status for Result Directory", e);
        throw new NotFoundException("Http result not available for query:" + queryHandle.toString());
      }
      String resultFSReadUrl = ctx.getConf().get(RESULT_FS_READ_URL);
      if (resultFSReadUrl != null) {
        try {
          URI resultReadPath = new URI(resultFSReadUrl + resultPath.toUri().getPath() + "?op=OPEN&user.name="
            + getSession(sessionHandle).getClusterUser());
          return Response.seeOther(resultReadPath)
            .header("content-disposition", "attachment; filename = " + resultPath.getName())
            .type(MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (URISyntaxException e) {
          throw new LensException(e);
        }
      } else {
        StreamingOutput stream = new StreamingOutput() {
          @Override
          public void write(OutputStream os) throws IOException {
            FSDataInputStream fin = null;
            try {
              FileSystem fs = resultPath.getFileSystem(ctx.getConf());
              fin = fs.open(resultPath);
              UtilityMethods.pipe(fin, os);
            } finally {
              if (fin != null) {
                fin.close();
              }

            }
          }
        };
        return Response.ok(stream).header("content-disposition", "attachment; filename = " + resultPath.getName())
          .type(MediaType.APPLICATION_OCTET_STREAM).build();
      }
    } else {
      String entity = "";
      if (result instanceof InMemoryResultSet || result instanceof PersistentResultSet) {
        entity = "Result is available in driver's "
          + (result instanceof InMemoryResultSet ? "memory" : "persistence") + ".";
      }
      return Response.status(Response.Status.NOT_FOUND).entity(entity).build();
    }
  }

  /**
   * Allow drivers to release resources acquired for a session if any.
   *
   * @param sessionHandle the session handle
   */
  public void closeDriverSessions(LensSessionHandle sessionHandle) {
    for (LensDriver driver : drivers.values()) {
      if (driver instanceof HiveDriver) {
        ((HiveDriver) driver).closeSession(sessionHandle);
      }
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.lens.server.LensService#closeSession(org.apache.lens.api.LensSessionHandle)
   */
  public void closeSession(LensSessionHandle sessionHandle) throws LensException {
    super.closeSession(sessionHandle);
    // Call driver session close in case some one closes sessions directly on query service
    closeDriverSessions(sessionHandle);
  }

  // Used in test code
  Collection<LensDriver> getDrivers() {
    return drivers.values();
  }

  @Override
  public long getQueuedQueriesCount() {
    return queuedQueries.size();
  }

  @Override
  public long getRunningQueriesCount() {
    return launchedQueries.getQueriesCount();
  }

  @Override
  public long getWaitingQueriesCount() {
    return waitingQueries.getQueriesCount();
  }

  @Override
  public long getFinishedQueriesCount() {
    return finishedQueries.size();
  }

  /**
   * Handle driver session start.
   *
   * @param event the event
   */
  protected void handleDriverSessionStart(DriverEvent event) {
    DriverSessionStarted sessionStarted = (DriverSessionStarted) event;
    if (!(event.getDriver() instanceof HiveDriver)) {
      return;
    }

    HiveDriver hiveDriver = (HiveDriver) event.getDriver();

    String lensSession = sessionStarted.getLensSessionID();
    LensSessionHandle sessionHandle = getSessionHandle(lensSession);
    if (sessionHandle == null) {
      log.warn("Lens session went away for sessionid:" + lensSession);
      return;
    }

    try {
      LensSessionImpl session = getSession(sessionHandle);
      acquire(sessionHandle);
      // Add resources for this session
      List<ResourceEntry> resources = session.getLensSessionPersistInfo().getResources();
      if (resources != null && !resources.isEmpty()) {
        for (ResourceEntry resource : resources) {
          log.info("Restoring resource {} for session {}", resource, lensSession);
          String command = "add " + resource.getType().toLowerCase() + " " + resource.getLocation();
          try {
            // Execute add resource query in blocking mode
            hiveDriver.execute(createResourceQuery(command, sessionHandle, hiveDriver));
            resource.restoredResource();
            log.info("Restored resource {} for session {}", resource, lensSession);
          } catch (Exception exc) {
            log.error("Unable to add resource {} for session {}", resource, lensSession, exc);
          }
        }
      } else {
        log.info("No resources to restore for session {}", lensSession);
      }
    } catch (Exception e) {
      log.warn(
        "Lens session went away! {} driver session: {}", lensSession,
        ((DriverSessionStarted) event).getDriverSessionID(), e);
    } finally {
      release(sessionHandle);
    }
  }

  /**
   * Add session's resources to selected driver if needed
   *
   * @param ctx QueryContext for executinf queries
   */
  protected void addSessionResourcesToDriver(final AbstractQueryContext ctx) {
    LensDriver driver = ctx.getSelectedDriver();
    String sessionIdentifier = ctx.getLensSessionIdentifier();

    if (!(driver instanceof HiveDriver) || StringUtils.isBlank(sessionIdentifier)) {
      // Adding resources only required for Hive driver
      return;
    }

    HiveDriver hiveDriver = (HiveDriver) driver;

    // Check if jars need to be passed to selected driver
    final LensSessionHandle sessionHandle = getSessionHandle(sessionIdentifier);
    final LensSessionImpl session = getSession(sessionHandle);

    // Add resources if either they haven't been marked as added on the session, or if Hive driver says they need
    // to be added to the corresponding hive driver
    if (!hiveDriver.areDBResourcesAddedForSession(sessionIdentifier, ctx.getDatabase())) {
      Collection<ResourceEntry> dbResources = session.getDBResources(ctx.getDatabase());

      if (CollectionUtils.isNotEmpty(dbResources)) {
        log.info("Proceeding to add resources for DB {} for query {} resources: {}", session.getCurrentDatabase(),
          ctx.getLogHandle(), dbResources);

        List<ResourceEntry> failedDBResources = addResources(dbResources, sessionHandle, hiveDriver);
        Iterator<ResourceEntry> itr = dbResources.iterator();
        while (itr.hasNext()) {
          ResourceEntry res = itr.next();
          if (!failedDBResources.contains(res)) {
            itr.remove();
          }
        }
      } else {
        log.info("No need to add DB resources for session: {} db= {}", sessionIdentifier, session.getCurrentDatabase());
      }
      hiveDriver.setResourcesAddedForSession(sessionIdentifier, ctx.getDatabase());
    }

    // Get pending session resources which needed to be added for this database
    Collection<ResourceEntry> pendingResources =
      session.getPendingSessionResourcesForDatabase(ctx.getDatabase());
    log.info("Adding pending {} session resources for session {} for database {}", pendingResources.size(),
      sessionIdentifier,  ctx.getDatabase());
    List<ResourceEntry> failedResources = addResources(pendingResources, sessionHandle, hiveDriver);
    // Mark added resources so that we don't add them again. If any of the resources failed
    // to be added, then they will be added again
    for (ResourceEntry res : pendingResources) {
      if (!failedResources.contains(res)) {
        res.addToDatabase(ctx.getDatabase());
      }
    }
  }

  /**
   * Add resources to hive driver, returning resources which failed to be added
   *
   * @param resources     collection of resources intented to be added to hive driver
   * @param sessionHandle
   * @param hiveDriver
   * @return resources which could not be added to hive driver
   */
  private List<ResourceEntry> addResources(Collection<ResourceEntry> resources,
    LensSessionHandle sessionHandle,
    HiveDriver hiveDriver) {
    List<ResourceEntry> failedResources = new ArrayList<ResourceEntry>();
    for (ResourceEntry res : resources) {
      try {
        addSingleResourceToHive(hiveDriver, res, sessionHandle);
      } catch (LensException exc) {
        failedResources.add(res);
        log.error("Error adding resources for session {} resources: {}", sessionHandle, res.getLocation(), exc);
      }
    }
    return failedResources;
  }

  private void addSingleResourceToHive(HiveDriver driver, ResourceEntry res,
    LensSessionHandle sessionHandle) throws LensException {
    String sessionIdentifier = sessionHandle.getPublicId().toString();
    String uri = res.getLocation();
    // Hive doesn't and URIs starting with file:/ correctly, so we have to change it to file:///
    // See: org.apache.hadoop.hive.ql.exec.Utilities.addToClassPath
    uri = removePrefixBeforeURI(uri);

    String command = "add " + res.getType().toLowerCase() + " " + uri;
    driver.execute(createResourceQuery(command, sessionHandle, driver));
    log.info("Added resource to hive driver for session {} cmd: {}", sessionIdentifier, command);
  }

  private boolean removeFromLaunchedQueries(final QueryContext finishedQuery) {

    /* Check javadoc of QueryExecutionServiceImpl#removalFromLaunchedQueriesLock for reason for existence
    of this lock. */

    log.debug("Acquiring lock in removeFromLaunchedQueries");
    removalFromLaunchedQueriesLock.lock();
    boolean modified = false;

    try {
      modified = this.launchedQueries.remove(finishedQuery);
    } finally {
      removalFromLaunchedQueriesLock.unlock();
    }

    log.debug("launchedQueries.remove(finishedQuery) has returned [{}] for finished query with query id:[{}]", modified,
        finishedQuery.getQueryHandleString());
    return modified;
  }

  /**
   * Caller of this method must make sure that this method is called inside a synchronized(queryContext) block
   * for a safe copy from queryContext to an instance of {@link FinishedLensQuery}
   *
   * @param queryContext
   */
  private void processWaitingQueriesAsync(final QueryContext queryContext) {

    final FinishedLensQuery finishedLensQuery = new FinishedLensQuery(queryContext);

    Runnable r = new Runnable() {
      @Override
      public void run() {
        try {
          logSegregationContext.setLogSegragationAndQueryId(finishedLensQuery.getHandle());
          processWaitingQueries(finishedLensQuery);
        } catch (final Throwable e) {
          log.error("Error in processing waiting queries", e);
        }
      }
    };

    exceptionSafeSubmit(this.waitingQueriesSelectionSvc, r);
  }

  private void exceptionSafeSubmit(final ExecutorService svc, final Runnable r) {
    try {
      svc.submit(r);
    } catch (final Throwable e) {
      log.debug("Could not submit runnable:{}", e);
    }
  }

  private void processWaitingQueries(final FinishedLensQuery finishedQuery) {

    Set<QueryContext> eligibleWaitingQueries = this.waitingQueriesSelector
        .selectQueries(finishedQuery, this.waitingQueries);

    if (eligibleWaitingQueries.isEmpty()) {
      log.debug("No queries eligible to move out of waiting state.");
      return;
    }

    waitingQueries.removeAll(eligibleWaitingQueries);
    queuedQueries.addAll(eligibleWaitingQueries);
    if (log.isDebugEnabled()) {
      log.debug("Added {} queries to queued queries", eligibleWaitingQueries.size());
    }
  }
}
