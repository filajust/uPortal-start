/* Copyright 2002 The JA-SIG Collaborative.  All rights reserved.
*  See license distributed with this file and
*  available online at http://www.uportal.org/license.html
*/

package org.jasig.portal.services;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.portal.ChannelDefinition;
import org.jasig.portal.UserProfile;
import org.jasig.portal.car.CarResources;
import org.jasig.portal.layout.IUserLayoutChannelDescription;
import org.jasig.portal.layout.IUserLayoutFolderDescription;
import org.jasig.portal.properties.PropertiesManager;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.services.stats.DoNothingStatsRecorderFactory;
import org.jasig.portal.services.stats.IStatsRecorder;
import org.jasig.portal.services.stats.IStatsRecorderFactory;
import org.jasig.portal.services.stats.RecordChannelAddedToLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordChannelDefinitionModifiedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelDefinitionPublishedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelDefinitionRemovedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelInstantiatedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelMovedInLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordChannelRemovedFromLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordChannelRenderedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelTargetedWorkerTask;
import org.jasig.portal.services.stats.RecordChannelUpdatedInLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordFolderAddedToLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordFolderMovedInLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordFolderRemovedFromLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordFolderUpdatedInLayoutWorkerTask;
import org.jasig.portal.services.stats.RecordLoginWorkerTask;
import org.jasig.portal.services.stats.RecordLogoutWorkerTask;
import org.jasig.portal.services.stats.RecordSessionCreatedWorkerTask;
import org.jasig.portal.services.stats.RecordSessionDestroyedWorkerTask;
import org.jasig.portal.services.stats.StatsRecorderLayoutEventListener;
import org.jasig.portal.services.stats.StatsRecorderSettings;
import org.jasig.portal.services.stats.StatsRecorderWorkerTask;
import org.jasig.portal.utils.threading.BoundedThreadPool;
import org.jasig.portal.utils.threading.ThreadPool;
import org.jasig.portal.utils.threading.WorkTracker;

/**
 * Stats recorder service. Various parts of the portal call
 * the methods in this service to record events such as 
 * when a user logs in, logs out, and subscribes to a channel.
 * The information is handed off in a separate thread
 * to an IStatsRecorder implementation that is determined
 * by the IStatsRecorderFactory implementation that can be
 * configured in portal.properties.
 * @author Ken Weiner, kweiner@unicon.net
 * @version $Revision$
 */
public class StatsRecorder {
  private static final Log log = LogFactory.getLog(StatsRecorder.class);
  protected static StatsRecorder statsRecorderInstance;
  protected StatsRecorderSettings statsRecorderSettings;
  protected IStatsRecorder statsRecorder;
  protected ThreadPool threadPool;
  
  /**
   * Constructor with private access so that the StatsRecorder
   * maintains only one instance of itself.
   */
  private StatsRecorder() {
      String statsRecorderFactoryName = null;
      IStatsRecorderFactory statsRecorderFactory = null;
      try {
          // Get a stats recorder from the stats recorder factory. 
          statsRecorderFactoryName = PropertiesManager.getProperty("org.jasig.portal.services.stats.StatsRecorderFactory.implementation");
          statsRecorderFactory = (IStatsRecorderFactory)CarResources.getInstance().getClassLoader().loadClass(statsRecorderFactoryName).newInstance();
      } catch (Exception e) {
          log.error( "Unable to instantiate stats recorder '" + statsRecorderFactoryName  + "'. Continuing with DoNothingStatsRecorder.", e);
          statsRecorderFactory = new DoNothingStatsRecorderFactory();          
      }
      try {
          statsRecorder = statsRecorderFactory.getStatsRecorder();
      
          // Get the stats recorder settings instance
          statsRecorderSettings = StatsRecorderSettings.instance();
      
          // Create a thread pool
          String prefix = this.getClass().getName() + ".threadPool_";
          int initialThreads = PropertiesManager.getPropertyAsInt(prefix + "initialThreads");
          int maxThreads = PropertiesManager.getPropertyAsInt(prefix + "maxThreads");
          int threadPriority = PropertiesManager.getPropertyAsInt(prefix + "threadPriority");
          threadPool = new BoundedThreadPool(initialThreads, maxThreads, threadPriority);
      } catch (Exception e) {
          log.error("Error instantiating StatsRecorder", e);
      }
  }  
  
  /**
   * Creates an instance of this stats recorder service.
   * @return a <code>StatsRecorder</code>
   * instance
   */
  private final static synchronized StatsRecorder instance() {
    if (statsRecorderInstance == null) { 
      statsRecorderInstance = new StatsRecorder(); 
    }
    return statsRecorderInstance;
  }
  
  /**
   * Creates an instance of a 
   * <code>StatsRecorderLayoutEventListener</code>.
   * @return a new stats recorder layout event listener instance
   */
  public final static StatsRecorderLayoutEventListener newLayoutEventListener(IPerson person, UserProfile profile) {
    return new StatsRecorderLayoutEventListener(person, profile);
  }  

  /**
   * Gets the value of a particular stats recorder setting.
   * Possible settings are available from <code>StatsRecorderSettings</code>.
   * For example: <code>StatsRecorder.get(StatsRecorderSettings.RECORD_LOGIN)</code>
   * @param setting the setting
   * @return the value for the setting
   */
  public static boolean get(int setting) {
    return instance().statsRecorderSettings.get(setting);
  }  
  
  /**
   * Sets the value of a particular stats recorder setting.
   * Possible settings are available from <code>StatsRecorderSettings</code>.
   * For example: <code>StatsRecorder.set(StatsRecorderSettings.RECORD_LOGIN, true)</code>
   * @param setting the setting to change
   * @param newValue the new value for the setting
   */
  public static void set(int setting, boolean newValue) {
    instance().statsRecorderSettings.set(setting, newValue);
  }  
  
  /**
   * Record the successful login of a user.
   * @param person the person who is logging in
   */
  public static void recordLogin(IPerson person) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_LOGIN)) {
      StatsRecorderWorkerTask task = new RecordLoginWorkerTask(person);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }

  /**
   * Record the logout of a user.
   * @param person the person who is logging out
   */
  public static void recordLogout(IPerson person) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_LOGOUT)) {
      StatsRecorderWorkerTask task = new RecordLogoutWorkerTask(person);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a new session is created for a user.
   * @param person the person whose session is being created
   */
  public static void recordSessionCreated(IPerson person) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_SESSION_CREATED)) {
      StatsRecorderWorkerTask task = new RecordSessionCreatedWorkerTask(person);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a user's session is destroyed
   * (when the user logs out or his/her session
   * simply times out)
   * @param person the person whose session is ending
   */
  public static void recordSessionDestroyed(IPerson person) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_SESSION_DESTROYED)) {
      StatsRecorderWorkerTask task = new RecordSessionDestroyedWorkerTask(person);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a new channel is being published
   * @param person the person publishing the channel
   * @param channelDef the channel being published
   */
  public static void recordChannelDefinitionPublished(IPerson person, ChannelDefinition channelDef) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_DEFINITION_PUBLISHED)) {
      StatsRecorderWorkerTask task = new RecordChannelDefinitionPublishedWorkerTask(person, channelDef);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  } 
  
  /**
   * Record that an existing channel is being modified
   * @param person the person modifying the channel
   * @param channelDef the channel being modified
   */
  public static void recordChannelDefinitionModified(IPerson person, ChannelDefinition channelDef) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_DEFINITION_MODIFIED)) {
      StatsRecorderWorkerTask task = new RecordChannelDefinitionModifiedWorkerTask(person, channelDef);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  
  
  /**
   * Record that a channel is being removed
   * @param person the person removing the channel
   * @param channelDef the channel being modified
   */
  public static void recordChannelDefinitionRemoved(IPerson person, ChannelDefinition channelDef) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_DEFINITION_REMOVED)) {
      StatsRecorderWorkerTask task = new RecordChannelDefinitionRemovedWorkerTask(person, channelDef);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  
  
  /**
   * Record that a channel is being added to a user layout
   * @param person the person adding the channel
   * @param profile the profile of the layout to which the channel is being added
   * @param channelDesc the channel being subscribed to
   */
  public static void recordChannelAddedToLayout(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_ADDED_TO_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordChannelAddedToLayoutWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }    
  
  /**
   * Record that a channel is being updated in a user layout
   * @param person the person updating the channel
   * @param profile the profile of the layout in which the channel is being updated
   * @param channelDesc the channel being updated
   */
  public static void recordChannelUpdatedInLayout(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_UPDATED_IN_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordChannelUpdatedInLayoutWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  

  /**
   * Record that a channel is being moved in a user layout
   * @param person the person moving the channel
   * @param profile the profile of the layout in which the channel is being moved
   * @param channelDesc the channel being moved
   */
  public static void recordChannelMovedInLayout(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_MOVED_IN_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordChannelMovedInLayoutWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a channel is being removed from a user layout
   * @param person the person removing the channel
   * @param profile the profile of the layout to which the channel is being added
   * @param channelDesc the channel being removed from a user layout
   */
  public static void recordChannelRemovedFromLayout(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_REMOVED_FROM_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordChannelRemovedFromLayoutWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a folder is being added to a user layout
   * @param person the person adding the folder
   * @param profile the profile of the layout to which the folder is being added
   * @param folderDesc the folder being subscribed to
   */
  public static void recordFolderAddedToLayout(IPerson person, UserProfile profile, IUserLayoutFolderDescription folderDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_FOLDER_ADDED_TO_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordFolderAddedToLayoutWorkerTask(person, profile, folderDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }    
  
  /**
   * Record that a folder is being updated in a user layout
   * @param person the person updating the folder
   * @param profile the profile of the layout in which the folder is being updated
   * @param folderDesc the folder being updated
   */
  public static void recordFolderUpdatedInLayout(IPerson person, UserProfile profile, IUserLayoutFolderDescription folderDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_FOLDER_UPDATED_IN_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordFolderUpdatedInLayoutWorkerTask(person, profile, folderDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  

  /**
   * Record that a folder is being moved in a user layout
   * @param person the person moving the folder
   * @param profile the profile of the layout in which the folder is being moved
   * @param folderDesc the folder being moved
   */
  public static void recordFolderMovedInLayout(IPerson person, UserProfile profile, IUserLayoutFolderDescription folderDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_FOLDER_MOVED_IN_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordFolderMovedInLayoutWorkerTask(person, profile, folderDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }
  
  /**
   * Record that a folder is being removed from a user layout
   * @param person the person removing the folder
   * @param profile the profile of the layout to which the folder is being added
   * @param folderDesc the folder being removed from a user layout
   */
  public static void recordFolderRemovedFromLayout(IPerson person, UserProfile profile, IUserLayoutFolderDescription folderDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_FOLDER_REMOVED_FROM_LAYOUT)) {
      StatsRecorderWorkerTask task = new RecordFolderRemovedFromLayoutWorkerTask(person, profile, folderDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  
  
  /**
   * Record that a channel is being instantiated
   * @param person the person for whom the channel is instantiated
   * @param profile the profile of the layout for whom the channel is instantiated
   * @param channelDesc the channel being instantiated
   */
  public static void recordChannelInstantiated(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_INSTANTIATED)) {
      StatsRecorderWorkerTask task = new RecordChannelInstantiatedWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  
  
  /**
   * Record that a channel is being rendered
   * @param person the person for whom the channel is rendered
   * @param profile the profile of the layout for whom the channel is rendered
   * @param channelDesc the channel being rendered
   */
  public static void recordChannelRendered(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_RENDERED)) {
      StatsRecorderWorkerTask task = new RecordChannelRenderedWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }  

  /**
   * Record that a channel is being targeted.  In other words,
   * the user is interacting with the channel via either a 
   * hyperlink or form submission.
   * @param person the person interacting with the channel
   * @param profile the profile of the layout in which the channel resides
   * @param channelDesc the channel being targeted
   */
  public static void recordChannelTargeted(IPerson person, UserProfile profile, IUserLayoutChannelDescription channelDesc) {
    if (instance().statsRecorderSettings.get(StatsRecorderSettings.RECORD_CHANNEL_TARGETED)) {
      StatsRecorderWorkerTask task = new RecordChannelTargetedWorkerTask(person, profile, channelDesc);
      task.setStatsRecorder(instance().statsRecorder);
      WorkTracker workTracker = instance().threadPool.execute(task);
    }
  }   
}
