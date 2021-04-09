package org.jeasy.flows.playbook;

import org.jeasy.flows.work.*;
import org.jeasy.flows.workflow.ParallelFlow;
import org.jeasy.flows.workflow.RepeatFlow;
import org.jeasy.flows.workflow.SequentialFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.jeasy.flows.workflow.ParallelFlow.Builder.aNewParallelFlow;
import static org.jeasy.flows.workflow.RepeatFlow.Builder.aNewRepeatFlow;
import static org.jeasy.flows.workflow.SequentialFlow.Builder.aNewSequentialFlow;

/**
 * The PlaybookManager initializes workflows from playbooks, and uses the WorkflowEngine to run them
 *
 * @author matt rajkowski
 */

public class PlaybookManager {

  final static long serialVersionUID = 8345648404174283570L;
  private static final Logger LOGGER = LoggerFactory.getLogger(PlaybookManager.class.getName());

  private static Map<String, Playbook> playbooks = new HashMap<>();
  private static Map<String, Object> taskInstances = new HashMap<>();

  public static void register(Map<String, String> taskLibrary) {
    LOGGER.info("Registering classes...");
    if (!taskLibrary.containsKey("set")) {
      taskLibrary.put("set", SetTask.class.getName());
    }
    if (!taskLibrary.containsKey("when")) {
      taskLibrary.put("when", WhenTask.class.getName());
    }
    if (!taskLibrary.containsKey("log")) {
      taskLibrary.put("log", LogTask.class.getName());
    }
    for (String className : taskLibrary.keySet()) {
      try {
        if (!taskInstances.containsKey(className)) {
          String taskClass = taskLibrary.get(className);
          Object classRef = Class.forName(taskClass).getDeclaredConstructor().newInstance();
          taskInstances.put(className, classRef);
          LOGGER.info("Added class: " + className + " = " + taskClass);
        }
      } catch (Exception e) {
        LOGGER.error("Class not found for '" + className + "': " + e.getMessage());
      }
    }
  }

  public static Playbook getPlaybook(String id) {
    return playbooks.get(id);
  }

  public static void add(Playbook playbook) {
    if (playbook == null) {
      LOGGER.error("Playbook is null");
      return;
    }
    playbooks.put(playbook.getId(), playbook);
  }

  public static void add(List<Playbook> playbookList) {
    if (playbookList == null) {
      LOGGER.error("Playbook list is null");
      return;
    }
    for (Playbook playbook : playbookList) {
      LOGGER.info("Adding playbook: " + playbook.getId());
      playbooks.put(playbook.getId(), playbook);
    }
  }

  public static WorkReport run(String playbookName) {
    return run(playbookName, null);
  }

  public static WorkReport run(String playbookName, WorkContext workContext) {
    Playbook playbook = playbooks.get(playbookName);
    return run(playbook, workContext);
  }

  public static WorkReport run(Playbook playbook) {
    return run(playbook, null);
  }

  public static WorkReport run(Playbook playbook, WorkContext workContext) {
    if (playbook == null || playbook.getTaskList().isEmpty()) {
      LOGGER.error("Playbook is null or empty...");
      return null;
    }
    if (taskInstances == null || taskInstances.isEmpty()) {
      register(new HashMap<>());
    }
    // Verify there is a matching work item and set the work item specific variables
    LOGGER.debug("Verifying playbook... '" + playbook.getId() + "'");
    for (Task task : playbook.getTaskList()) {
      LOGGER.debug("Checking: " + task.getId());
      if ("block".equals(task.getId()) || "parallel".equals(task.getId())) {
        // Verify the referenced TaskList
        for (Task blockTask : task.getTaskList()) {
          LOGGER.debug("  Checking: " + blockTask.getId());
          if (!taskInstances.containsKey(blockTask.getId())) {
            LOGGER.error("Block Task id not found: " + blockTask.getId());
            return null;
          }
        }
      } else if (!taskInstances.containsKey(task.getId())) {
        LOGGER.error("Task id not found: " + task.getId());
        return null;
      }
    }
    // Make sure workContext exists and has playbook vars
    if (workContext == null) {
      workContext = new WorkContext(playbook);
    }

    LOGGER.debug("Building workflow...");
    SequentialFlow.Builder.NameStep builder = aNewSequentialFlow();
    builder.named(playbook.getId());

    SequentialFlow.Builder.ThenStep thenStep = null;
    for (Task task : playbook.getTaskList()) {
      // Create a TaskContext which contains objects for the task to use
      TaskContext taskContext = createTaskContext(task, (Work) taskInstances.get(task.getId()));
      // Determine if the task contains sub-tasks, for conditional or parallel tasks
      if (task.hasTasks()) {
        if ("block".equals(task.getId())) {
          LOGGER.debug("Creating a SequentialFlow...");
          SequentialFlow.Builder.NameStep blockBuilder = aNewSequentialFlow();
          blockBuilder.named("block");
          SequentialFlow.Builder.ThenStep blockThenStep = null;
          for (Task blockTask : task.getTaskList()) {
            TaskContext blockTaskContext = createTaskContext(blockTask, (Work) taskInstances.get(blockTask.getId()));
            if (blockTask.getRepeat() > 0) {
              LOGGER.debug("Creating a RepeatFlow...");
              RepeatFlow repeatFlow = aNewRepeatFlow()
                  .repeat(blockTaskContext)
                  .times(blockTask.getRepeat())
                  .build();
              blockThenStep = blockBuilder.execute(repeatFlow);
            } else {
              blockThenStep = blockBuilder.execute(blockTaskContext);
            }
          }
          SequentialFlow blockFlow = blockThenStep.build();
          thenStep = builder.execute(blockFlow);
        } else if ("parallel".equals(task.getId())) {
          // Construct a parallel workflow and append as a thenStep
          List<TaskContext> parallelTaskContextList = new ArrayList<>();
          for (Task parallelTask : task.getTaskList()) {
            TaskContext parallelTaskContext = createTaskContext(parallelTask, (Work) taskInstances.get(parallelTask.getId()));
            parallelTaskContextList.add(parallelTaskContext);
          }
          ExecutorService executorService = Executors.newFixedThreadPool(task.getThreads());
          LOGGER.trace("Executor thread count: " + task.getThreads());
          LOGGER.debug("Creating a ParallelFlow...");
          ParallelFlow parallelFlow = aNewParallelFlow()
              .named(task.getId())
              .execute(parallelTaskContextList)
              .with(executorService)
              .timeout(task.getTimeout(), TimeUnit.SECONDS)
              .build();
          thenStep = builder.execute(parallelFlow);
        } else {
          LOGGER.error("Unknown '" + task.getId() + "': This TASK has hanging TASKS!!");
        }
      } else {
        // Just a task to be added
        if (task.getRepeat() > 0) {
          LOGGER.debug("Creating a RepeatFlow...");
          RepeatFlow repeatFlow = aNewRepeatFlow()
              .repeat(taskContext)
              .times(task.getRepeat())
              .build();
          thenStep = builder.execute(repeatFlow);
        } else {
          thenStep = builder.execute(taskContext);
        }
      }
    }
    // Ready to execute...
    SequentialFlow sequentialFlow = thenStep.build();
    LOGGER.info("Executing workflow... " + sequentialFlow.getName());
    return sequentialFlow.execute(workContext);
  }

  private static TaskContext createTaskContext(Task task, Work work) {
    TaskContext taskContext = new TaskContext(work);
    taskContext.setData(task.getData());
    taskContext.put(task.getVars());
    taskContext.setWhen(task.getWhen());
    return taskContext;
  }

}
