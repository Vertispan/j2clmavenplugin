This library is meant to serve as a simple way to build j2cl+closure output, caching work where necessary.
It can serve as a reference when implementing in a "real" build system like Gradle, or can be used to help
a build system like Maven be able to cache intermediate outputs and watch for changes, only remaking what
is necessary.

The API is designed to let separate Projects/modules/packages depend on each other (without cycles), either
at compile time, runtime, or both. Annotation processors are not currently considered to be separate (serving
the least common denominator of Maven).

With projects created, a build can be requested. The first step is hash any file in the projects, and then the
tasks can be started. Each task specifies the inputs it needs and configuration it uses to do its work, and 
each build, those are hashed to see if the work needs to be performed again. When files are changed on disk,
those file hashes then can affect any other task which was relying on it as input.

There is also a separate API for consumers of the build to replace specific tasks. A new TaskFactory can
be declared to produce the desired outputs, and this can be specified at runtime (through maven plugin
configuration, for example). New output types can be specified as well, to circumvent the usual tasks, or
to introduce new intermediate tasks (for sharing work or adding parallelism).

The BuildService is given a set of projects that need to be built - when asked to watch, it will defer to
a watch service. BuildService starts up by collecting tasks from the projects and their dependencies, and
ensuring that hashes of all sources are ready, to be used by either when watching for changes or to cache
outputs from various steps. When something a task needs to be run, it is up to BuildService to schedule 
that task and any downstream dependencies - it is up to the build service to make sure enough work is 
specified in this request.

Tasks can be scheduled to run through the TaskScheduler - a given task should be provided along with its
dependency information, so that the scheduler can ensure that the earlier work is done. Of the tasks it is
given to run at a time, the scheduler first must attempt those with no dependencies also present in the list.
When considering a task, the scheduler asks the DiskCache if the task is already finished, if so we move on
(either failing if the task failed, or continuing to downstream tasks). If the task is in progress, we wait
for it to complete (this should imply another process working on it), otherwise we mark it as created and
start the work. Starting that task first requires confirming that all dependencies are ready (otherwise this 
suggests an internal error or the cache is broken), then performing the work (probably off-thread). The
TaskScheduler is meant to be replaceable, to offer a new implementation on how work is scheduled.

The DiskCache provides insight into which tasks have already been completed and cached, and can provide
log details on previously successful or failed work. It is meant to coordinate work between different processes
as well. It is meant to be extensible by offering a new mechanism by which a task's hash is computed, and the
directory structure that is used to lead to outputs.