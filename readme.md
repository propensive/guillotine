[<img alt="GitHub Workflow" src="https://img.shields.io/github/actions/workflow/status/propensive/guillotine/main.yml?style=for-the-badge" height="24">](https://github.com/propensive/guillotine/actions)
[<img src="https://img.shields.io/discord/633198088311537684?color=8899f7&label=DISCORD&style=for-the-badge" height="24">](https://discord.gg/7b6mpF6Qcf)
<img src="/doc/images/github.png" valign="middle">

# Guillotine

__A small library for executing stuff__

Guillotine makes it easy to work with shell processes in Scala, with simple interpolation-based
definitions of commands, and type-based interpretation of their output. For example, a directory
listing may be obtained with `sh"ls $path".exec[List[String]]()`.

## Features

- lightweight syntax for expressing shell commands, e.g. `sh"ls"`
- typeclasses define how different types are substituted into commands
- compile-time checking of command syntax, with correct handling of quotes and substitutions
- typeclass-based execution strategies for commands
- shell command results may be interpreted as `String`, `Int`, `Unit` or streamed as `LazyList`
- processes may be started synchronously or asynchronously
- commands may be piped to other commands with the `|` operator, or applied like functions


## Availability Plan

Guillotine has not yet been published. The medium-term plan is to build Guillotine
with [Fury](https://github.com/propensive/fury) and to publish it as a source build on
[Vent](https://github.com/propensive/vent). This will enable ordinary users to write and build
software which depends on Guillotine.

Subsequently, Guillotine will also be made available as a binary in the Maven
Central repository. This will enable users of other build tools to use it.

For the overeager, curious and impatient, see [building](#building).

## Getting Started

### Commands

Shell commands are created using the `sh""` interpolator, which will interpret (at compiletime) a
command and its arguments, correctly interpreting single- and double-quoted arguments and escaped
characters. Unclosed quotes will result in a compile error.

Substitutions of values of a variety of different types may be made into an `sh` interpolator, and
may be read as either "single-argument" (such as `String` or `Int`) or "multi-argument" (such as
`List[Int]` or `Set[String]`).

Multi-argument substitutions will be interpreted as multiple arguments to the shell command unless
they are enclosed within quotes, in which case they will be interpreted as a space-separated
string.

Substitutions should normally be surrounded by spaces, otherwise they will be prepended or appended
to adjacent arguments.

#### Piping

Two commands may be combined using the pipe operator (`|`), for example,
```scala
sh"cat /home/work/file" | sh"grep $query" | sh"wc -l"
```
which is equivalent to the single shell command, `cat /home/work/file | grep $query | wc -l`, with
the appropriate substitution of `query` being made.

While this expression will seem very familiar from a shell-command perspective, it may also be
written in function application style as,
```scala
sh"wc -l"(sh"grep $query"(sh"cat /home/work/file"))
```
and the two versions are equivalent.

#### Substitutions

Substitutions of a variety of different types may be made into an interpolated `sh` command. Any
type for which a `gossamer.Show` typeclass exists will be inserted as a single parameter, and
any sequence of one of these types will be inserted as multiple arguments. A `Command` instance
may also be substituted into another, for example,
```scala
val echo = sh"echo Hello World"
sh"sh -c '$echo'"
```
where the quotes are required around `'$echo'` so that the command is passed to `sh -c` as a
single argument, rather than multiple arguments (of which only the first would be used).

#### Environment

Execution requires an `Env` instance specifying a map of environment variables and a working
directory as a `String`, and should be specified as a contextual value, for example,
```scala
given Env(Map("PATH" -> "/usr/bin:/usr/sbin"), "/home/work")
```
however it is common to use the `enclosing` environment. That is, to pass the environment in which
the JVM was started to its subprocess, ensuring that processes started by Guillotine behave as they
would if started directly from the shell. There may, however, be security implications when doing
this, so it must be explicitly enabled with:
```scala
given Env = envs.enclosing
```

### Execution

Two methods are provided for starting execution of a process: `fork` and `exec`, both taking a type
parameter which determines the type of the return value, and may also affect how execution is
handled.

```scala
val result: String = sh"echo Hello World".exec[String]()
```

The `exec` method will return a value synchronously, when that value is ready. This may happen only
when the process completes execution, if the entire output is caputured, for example if the
return-type is `String`, or may happen earlier if a streaming return type, such as
`LazyList[String]`, is specified.

The `fork` method always starts the process asynchronously, and returns an instance of `Process[T]`,
where `T` is the specified return type.

```scala
val process: Process[String] = sh"locate lostfile".fork[String]()
```

`Process` implements a few useful methods for working with a running process:

 - `await()` which waits until the process completes, and returns its result of type `T`
 - `abort()` which stopes execution, by delegating to Java's `Process#destroy`
 - `kill()` which stopes execution, by delegating to Java's `Process#destroyForcibly`
 - `pid` which returns a `Pid` instance representing the OS-dependent process ID
 - `stdout` and `stderr` methods for directly accessing the process's output streams; these methods
   both take an integer parameter limiting the number of bytes that may be read from the stream,
   defaulting to `10MB`
 - `stdin(in)` which accepts a stream of bytes (`LazyList[IArray[Byte]]`) as standard input to the
   process

The synchronous `exec[T]()` method is always equivalent to `fork[T]().await()`.

### Result interpretation

Different shell processes may behave differently in how their results should be interpreted. Those
differences include the interpretation of the exit status—where different nonzero codes may be
interpreted as different types of failure—and which stream contains the important output, `STDOUT`
or `STDERR`.

How these differences are interpreted is determined by the choice of return type: nonzero return
types may be presented as thrown exceptions, or interpreted as a different sort of "success".
Furthermore, the return type will determine whether the result may be return before the shell
process terminates, or whether (in the case of a streaming response) it may be returned earlier.

The `Executor[T]` typeclass provides support for producing different return types. Executors for
the following types are provided:
- `String`, which interprets the response using the system encoding and returns a value after the
  process terminates
- `LazyList[String]`, which provides a stream of lines of text (without the newline character)
- `LazyList[IArray[Byte]]`, which returns a stream of byte arrays
- `Unit`, to be used when the result is not important
- `ExitStatus`, an enumeration of `Ok` or `Fail(status)` where `status` is a nonzero positive
  integer

Custom executors may be provided by implementing the `Executor` trait with the single abstract
method,
```scala
def interpret(process: java.lang.Process): T
```
or by mapping across an existing `Executor`, for example,
```scala
given Executor[Int] = summon[Executor[String]].map(_.toInt)
```
since all `Executor`s are functors.





## Status

Guillotine is classified as __maturescent__. For reference, Soundness projects are
categorized into one of the following five stability levels:

- _embryonic_: for experimental or demonstrative purposes only, without any guarantees of longevity
- _fledgling_: of proven utility, seeking contributions, but liable to significant redesigns
- _maturescent_: major design decisions broady settled, seeking probatory adoption and refinement
- _dependable_: production-ready, subject to controlled ongoing maintenance and enhancement; tagged as version `1.0.0` or later
- _adamantine_: proven, reliable and production-ready, with no further breaking changes ever anticipated

Projects at any stability level, even _embryonic_ projects, can still be used,
as long as caution is taken to avoid a mismatch between the project's stability
level and the required stability and maintainability of your own project.

Guillotine is designed to be _small_. Its entire source code currently consists
of 562 lines of code.

## Building

Guillotine will ultimately be built by Fury, when it is published. In the
meantime, two possibilities are offered, however they are acknowledged to be
fragile, inadequately tested, and unsuitable for anything more than
experimentation. They are provided only for the necessity of providing _some_
answer to the question, "how can I try Guillotine?".

1. *Copy the sources into your own project*
   
   Read the `fury` file in the repository root to understand Guillotine's build
   structure, dependencies and source location; the file format should be short
   and quite intuitive. Copy the sources into a source directory in your own
   project, then repeat (recursively) for each of the dependencies.

   The sources are compiled against the latest nightly release of Scala 3.
   There should be no problem to compile the project together with all of its
   dependencies in a single compilation.

2. *Build with [Wrath](https://github.com/propensive/wrath/)*

   Wrath is a bootstrapping script for building Guillotine and other projects in
   the absence of a fully-featured build tool. It is designed to read the `fury`
   file in the project directory, and produce a collection of JAR files which can
   be added to a classpath, by compiling the project and all of its dependencies,
   including the Scala compiler itself.
   
   Download the latest version of
   [`wrath`](https://github.com/propensive/wrath/releases/latest), make it
   executable, and add it to your path, for example by copying it to
   `/usr/local/bin/`.

   Clone this repository inside an empty directory, so that the build can
   safely make clones of repositories it depends on as _peers_ of `guillotine`.
   Run `wrath -F` in the repository root. This will download and compile the
   latest version of Scala, as well as all of Guillotine's dependencies.

   If the build was successful, the compiled JAR files can be found in the
   `.wrath/dist` directory.

## Contributing

Contributors to Guillotine are welcome and encouraged. New contributors may like
to look for issues marked
[beginner](https://github.com/propensive/guillotine/labels/beginner).

We suggest that all contributors read the [Contributing
Guide](/contributing.md) to make the process of contributing to Guillotine
easier.

Please __do not__ contact project maintainers privately with questions unless
there is a good reason to keep them private. While it can be tempting to
repsond to such questions, private answers cannot be shared with a wider
audience, and it can result in duplication of effort.

## Author

Guillotine was designed and developed by Jon Pretty, and commercial support and
training on all aspects of Scala 3 is available from [Propensive
O&Uuml;](https://propensive.com/).



## Name

Guillotine is named after the Eighteenth Century execution apparatus, as both perform executions.

In general, Soundness project names are always chosen with some rationale,
however it is usually frivolous. Each name is chosen for more for its
_uniqueness_ and _intrigue_ than its concision or catchiness, and there is no
bias towards names with positive or "nice" meanings—since many of the libraries
perform some quite unpleasant tasks.

Names should be English words, though many are obscure or archaic, and it
should be noted how willingly English adopts foreign words. Names are generally
of Greek or Latin origin, and have often arrived in English via a romance
language.

## Logo

The logo shows the principal parts of a guillotine: the lunette and the blade.

## License

Guillotine is copyright &copy; 2024 Jon Pretty & Propensive O&Uuml;, and
is made available under the [Apache 2.0 License](/license.md).

