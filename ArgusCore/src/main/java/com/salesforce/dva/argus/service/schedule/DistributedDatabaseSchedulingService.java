/*
 * Copyright (c) 2016, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.dva.argus.service.schedule;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import com.salesforce.dva.argus.entity.Alert;
import com.salesforce.dva.argus.entity.DistributedSchedulingLock;
import com.salesforce.dva.argus.entity.ServiceManagementRecord;
import com.salesforce.dva.argus.entity.ServiceManagementRecord.Service;
import com.salesforce.dva.argus.inject.SLF4JTypeListener;
import com.salesforce.dva.argus.service.AlertService;
import com.salesforce.dva.argus.service.AuditService;
import com.salesforce.dva.argus.service.DefaultService;
import com.salesforce.dva.argus.service.DistributedSchedulingLockService;
import com.salesforce.dva.argus.service.GlobalInterlockService.LockType;
import com.salesforce.dva.argus.service.SchedulingService;
import com.salesforce.dva.argus.service.ServiceManagementService;
import com.salesforce.dva.argus.service.UserService;
import com.salesforce.dva.argus.service.alert.AlertDefinitionsCache;
import com.salesforce.dva.argus.system.SystemConfiguration;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.salesforce.dva.argus.system.SystemAssert.requireArgument;

/**
 * Implementation of Distributed scheduling using database
 * 
 * @author Raj Sarkapally rsarkapally@salesforce.com
 *
 */
@Singleton
public class DistributedDatabaseSchedulingService extends DefaultService implements SchedulingService {


	//~ Instance fields ******************************************************************************************************************************

	@SLF4JTypeListener.InjectLogger
	private Logger _logger;
	private final AlertService _alertService;
	private final UserService _userService;
	private final ServiceManagementService _serviceManagementRecordService;
	private final AuditService _auditService;
	private final BlockingQueue<Alert> alertsQueue = new LinkedBlockingQueue<Alert>();
	private ExecutorService _schedulerService;
	private Thread _alertSchedulingThread;
	private SystemConfiguration _configuration;
	private final DistributedSchedulingLockService _distributedSchedulingService;
	private AlertDefinitionsCache _alertDefinitionsCache;
	private static final Integer ALERT_SCHEDULING_BATCH_SIZE = 100;

	//~ Constructors *********************************************************************************************************************************

	/**
	 * Creates a new DefaultSchedulingService object.
	 *
	 * @param  alertService                    The alert service instance to use. Cannot be null.
	 * @param  userService                     The user service instance to use. Cannot be null.
	 * @param  serviceManagementRecordService  The serviceManagementRecordService instance to use. Cannot be null.
	 * @param  auditService                    The audit service. Cannot be null.
	 * @param  config                          The system configuration used to configure the service.
	 */
	@Inject
	DistributedDatabaseSchedulingService(AlertService alertService, UserService userService,
			ServiceManagementService serviceManagementRecordService, AuditService auditService, SystemConfiguration config, DistributedSchedulingLockService distributedSchedulingLockService) {
		super(config);
		requireArgument(alertService != null, "Alert service cannot be null.");
		requireArgument(userService != null, "User service cannot be null.");
		requireArgument(serviceManagementRecordService != null, "Service management record service cannot be null.");
		requireArgument(auditService != null, "Audit service cannot be null.");
		requireArgument(config != null, "System configuration cannot be null.");
		_alertService = alertService;
		_userService = userService;
		_serviceManagementRecordService = serviceManagementRecordService;
		_auditService = auditService;
		_configuration = config;
		_distributedSchedulingService=distributedSchedulingLockService;
		_alertDefinitionsCache = new AlertDefinitionsCache(_alertService);

		// initializing the alert scheduler tasks
		int numThreads = Integer.parseInt(_configuration.getValue(Property.QUARTZ_THREADPOOL_COUNT.getName(), Property.QUARTZ_THREADPOOL_COUNT.getDefaultValue()));
		_schedulerService = Executors.newFixedThreadPool(numThreads,
				new ThreadFactory() {
			public Thread newThread(Runnable r) {
				Thread t = Executors.defaultThreadFactory().newThread(r);
				t.setDaemon(true);
				return t;
			}
		});
		for(int i=0;i<numThreads;i++) {
			_schedulerService.submit(new AlertScheduler());
		}
	}

	//~ Methods **************************************************************************************************************************************

	@Override
	public synchronized void startAlertScheduling() {
		requireNotDisposed();
		if (_alertSchedulingThread != null && _alertSchedulingThread.isAlive()) {
			_logger.info("Request to start alert scheduling aborted as it is already running.");
		} else {
			_logger.info("Starting alert scheduling thread.");
			_alertSchedulingThread = new SchedulingThread("schedule-alerts", LockType.ALERT_SCHEDULING);
			_alertSchedulingThread.start();
			_logger.info("Alert scheduling thread started.");
		}
	}

	@Override
	public synchronized void dispose() {
		stopAlertScheduling();
		super.dispose();
		_serviceManagementRecordService.dispose();
		_alertService.dispose();
		_userService.dispose();
	}

	@Override
	public synchronized void stopAlertScheduling() {
		requireNotDisposed();
		if (_alertSchedulingThread != null && _alertSchedulingThread.isAlive()) {
			_logger.info("Stopping alert scheduling");
			_alertSchedulingThread.interrupt();
			_logger.info("Alert scheduling thread interrupted.");
			try {
				_logger.info("Waiting for alert scheduling thread to terminate.");
				_alertSchedulingThread.join();
			} catch (InterruptedException ex) {
				_logger.warn("Alert job scheduler was interrupted while shutting down.");
			}
			_logger.info("Alert job scheduling stopped.");
		} else {
			_logger.info("Requested shutdown of alert scheduling aborted as it is not yet running.");
		}
	}

	@Override
	@Transactional
	public synchronized void enableScheduling() {
		requireNotDisposed();
		_logger.info("Globally enabling all scheduling.");
		_setServiceEnabled(true);
		_logger.info("All scheduling globally enabled.");
	}

	@Override
	@Transactional
	public synchronized void disableScheduling() {
		requireNotDisposed();
		_logger.info("Globally disabling all scheduling.");
		_setServiceEnabled(false);
		_logger.info("All scheduling globally disabled.");
	}

	@Transactional
	private boolean _isSchedulingServiceEnabled() {
		synchronized (_serviceManagementRecordService) {
			return _serviceManagementRecordService.isServiceEnabled(Service.SCHEDULING);
		}
	}

	/**
	 * Enables the scheduling service.
	 *
	 * @param  enabled  True to enable, false to disable.
	 */
	@Transactional
	protected void _setServiceEnabled(boolean enabled) {
		synchronized (_serviceManagementRecordService) {
			ServiceManagementRecord record = _serviceManagementRecordService.findServiceManagementRecord(Service.SCHEDULING);

			if (record == null) {
				record = new ServiceManagementRecord(_userService.findAdminUser(), Service.SCHEDULING, enabled);
			}
			record.setEnabled(enabled);
			_serviceManagementRecordService.updateServiceManagementRecord(record);
		}
	}

	//~ Enums ****************************************************************************************************************************************

	/**
	 * The implementation specific configuration properties.
	 *
	 * @author   Raj Sarkapally (rsarkapally@salesforce.com)
	 */
	public enum Property {

		/** Specifies the number of threads used for scheduling.  Defaults to 1. */
		QUARTZ_THREADPOOL_COUNT("service.property.scheduling.quartz.threadPool.threadCount", "10"),
		JOBS_BLOCK_SIZE("service.property.scheduling.jobsBlockSize", "1000"),
		SCHEDULING_REFRESH_INTERVAL_IN_MILLS("service.property.scheduling.schedulingRefeshInterval", "60000"),
		SLEEP_TIME_BEFORE_GETTING_NEXT_JOB_BLOCK_IN_MILLS("service.property.scheduling.sleepTimeBeforeGettingNextJobBlock", "100"),
		MAX_JOBS_PER_SCHEDULER("service.property.scheduling.maxJobsPerScheduler", "10000");

		private final String _name;
		private final String _defaultValue;

		private Property(String name, String defaultValue) {
			_name = name;
			_defaultValue = defaultValue;
		}

		/**
		 * Returns the name of the property.
		 *
		 * @return  The name of the property.
		 */
		public String getName() {
			return _name;
		}

		/**
		 * Returns the default property value.
		 *
		 * @return The default property value.
		 */
		public String getDefaultValue() {
			return _defaultValue;
		}
	}

	//~ Inner Classes ********************************************************************************************************************************

	/**
	 * Job scheduler.
	 *
	 * @author  Raj Sarkapally (rsarkapally@salesforce.com)
	 */
	private class SchedulingThread extends Thread {

		private final LockType lockType;

		/**
		 * Creates a new SchedulingThread object.
		 *
		 * @param  name      The name of the thread.
		 * @param  Schedulingtype  Type of the schedule. Cannot be null.
		 */
		public SchedulingThread(String name, LockType lockType) {
			super(name);
			this.lockType = lockType;
		}

		@Override
		public void run() {

			int jobsBlockSize=Integer.parseInt(_configuration.getValue(Property.JOBS_BLOCK_SIZE.getName(), Property.JOBS_BLOCK_SIZE.getDefaultValue()));
			long schedulingRefreshTime = Long.parseLong(_configuration.getValue(Property.SCHEDULING_REFRESH_INTERVAL_IN_MILLS.getName(), Property.SCHEDULING_REFRESH_INTERVAL_IN_MILLS.getDefaultValue()));

			if (_isSchedulingServiceEnabled()) {
				// wait for the alert definitions cache to be loaded
				while(!_alertDefinitionsCache.isAlertsCacheInitialized()) {
					_logger.info("Waiting for alerts cache to be initialized. Sleeping for 5 seconds..");
					try {
						Thread.sleep(5*1000);
					} catch (InterruptedException e) {
		                _logger.error("Thread interrupted when sleeping - " + ExceptionUtils.getFullStackTrace(e));
					}
				}
				while (!isInterrupted()) {
					DistributedSchedulingLock distributedSchedulingLock = _distributedSchedulingService.updateNGetDistributedScheduleByType(LockType.ALERT_SCHEDULING,jobsBlockSize,schedulingRefreshTime);
					long nextStartTime = distributedSchedulingLock.getNextScheduleStartTime();
					int jobsFromIndex = distributedSchedulingLock.getCurrentIndex() - jobsBlockSize; 

					if(jobsFromIndex < distributedSchedulingLock.getJobCount()){
						long startTimeForCurrMinute = nextStartTime;
						if(startTimeForCurrMinute>System.currentTimeMillis()) {
							startTimeForCurrMinute = startTimeForCurrMinute - 60*1000;
						}
						List<Alert> enabledAlerts = _alertDefinitionsCache.getEnabledAlertsForMinute(startTimeForCurrMinute);
						// schedule all the jobs by putting them in scheduling queue
						_logger.info("Scheduling {} enabled alerts for the minute starting at {}", enabledAlerts.size(), startTimeForCurrMinute);
						alertsQueue.addAll(enabledAlerts);

						if(System.currentTimeMillis()<nextStartTime) {
							_logger.info("All jobs for the current minute are scheduled already. Scheduler is sleeping for {} millis", (nextStartTime - System.currentTimeMillis()));
							_sleep(distributedSchedulingLock.getNextScheduleStartTime()-System.currentTimeMillis());
						}
					} else if(System.currentTimeMillis() < nextStartTime) {
						_logger.info("All jobs for the current minute are scheduled already. Scheduler is sleeping for {} millis", (nextStartTime - System.currentTimeMillis()));
						_sleep(distributedSchedulingLock.getNextScheduleStartTime()-System.currentTimeMillis());
					}

				}
			}
		}

		private void _sleep(long millis) {
			try {
				sleep(millis);
			} catch (InterruptedException ex) {
				_logger.warn("Scheduling was interrupted.");
				interrupt();
			}
		}
	}

	class AlertScheduler implements Runnable{
		@Override
		public void run() {
			List<Alert> alertsBatch = new ArrayList<Alert>();
			while(true) {
				try {
					Alert alert = alertsQueue.poll(10, TimeUnit.MILLISECONDS);
                    if(alert!=null) {
                    	    alertsBatch.add(alert);
                    }
					if((alert==null && alertsBatch.size()>0) || alertsBatch.size()==ALERT_SCHEDULING_BATCH_SIZE) {
						_alertService.enqueueAlerts(alertsBatch);
						alertsBatch = new ArrayList<Alert>();
					}
				}catch(Exception e) {
					_logger.error("Exception occured when scheduling alerts - "+ ExceptionUtils.getFullStackTrace(e));
				}
			}
		}

	}
}
/* Copyright (c) 2018, Salesforce.com, Inc.  All rights reserved. */