/*
    Guillotine, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package guillotine

import contextual.*
import rudiments.*
import turbulence.*
import gossamer.*
import spectacular.*
import eucalyptus.*
import escapade.*
import iridescence.*
import ambience.*
import anticipation.*

import scala.jdk.StreamConverters.StreamHasToScala
import scala.quoted.*
import scala.compiletime.*

import annotation.targetName
import java.io as ji

import language.experimental.captureChecking

enum Context:
  case Awaiting, Unquoted, Quotes2, Quotes1

case class State(current: Context, esc: Boolean, args: List[Text])

object CommandOutput extends PosixCommandOutputs

erased trait CommandOutput[+ExecType <: Label, +ResultType]

object Executor:
  given stream: Executor[LazyList[Text]] = proc =>
    val reader = ji.BufferedReader(ji.InputStreamReader(proc.getInputStream))
    reader.lines().nn.toScala(LazyList).map(_.show)
  
  given text: Executor[Text] = proc =>
    val buf: StringBuilder = StringBuilder()
    stream.interpret(proc).map(_.s).foreach(buf.append(_))
    Text(buf.toString)

  given dataStream(using streamCut: CanThrow[StreamCutError]): Executor[LazyList[Bytes]] =
    proc => Readable.inputStream.read(proc.getInputStream.nn)
  
  given exitStatus: Executor[ExitStatus] = _.waitFor() match
    case 0     => ExitStatus.Ok
    case other => ExitStatus.Fail(other)
  
  given unit: Executor[Unit] = exitStatus.map(_ => ())

@capability 
trait Executor[ResultType]:
  def interpret(process: java.lang.Process): ResultType
  
  def map[ResultType2](fn: ResultType => ResultType2): Executor[ResultType2] =
    process => fn(interpret(process))

class Process[+ExecType <: Label, ResultType](process: java.lang.Process):
  def pid: Pid = Pid(process.pid)
  
  def stdout(): LazyList[Bytes] throws StreamCutError =
    Readable.inputStream.read(process.getInputStream.nn)
  
  def stderr(): LazyList[Bytes] throws StreamCutError =
    Readable.inputStream.read(process.getErrorStream.nn)
  
  def stdin
      [ChunkType]
      (stream: LazyList[ChunkType]^)
      (using writable: Writable[ji.OutputStream, ChunkType])
      : Unit^{stream, writable} =
    writable.write(process.getOutputStream.nn, stream)

  def await()(using executor: Executor[ResultType]): ResultType^{executor} =
    executor.interpret(process)
  
  def exitStatus(): ExitStatus = process.waitFor() match
    case 0     => ExitStatus.Ok
    case other => ExitStatus.Fail(other)
  
  def abort()(using Log): Unit =
    Log.info(out"The process with PID $pid was aborted")
    process.destroy()
  
  def kill()(using Log): Unit =
    Log.warn(out"The process with PID $pid was killed")
    process.destroyForcibly()

sealed trait Executable:
  type Exec <: Label

  def fork
      [ResultType]
      ()
      (using properties: SystemProperties, systemProperty: CanThrow[SystemPropertyError], log: Log)
      : Process[Exec, ResultType]
  
  def exec
      [ResultType]
      ()(using properties: SystemProperties, systemProperty: CanThrow[SystemPropertyError], log: Log)
      (using executor: Executor[ResultType])
      : ResultType^{executor} =
    fork[ResultType]().await()

  def apply
      [ResultType]
      ()
      (using erased commandOutput: CommandOutput[Exec, ResultType])
      (using properties: SystemProperties, systemProperty: CanThrow[SystemPropertyError], log: Log)
      (using executor: Executor[ResultType])
      : ResultType^{executor} =
    fork[ResultType]().await()

  def apply(cmd: Executable): Pipeline = cmd match
    case Pipeline(cmds*) => this match
      case Pipeline(cmds2*) => Pipeline((cmds ++ cmds2)*)
      case cmd: Command     => Pipeline((cmds :+ cmd)*)
    case cmd: Command    => this match
      case Pipeline(cmds2*) => Pipeline((cmd +: cmds2)*)
      case cmd2: Command    => Pipeline(cmd, cmd2)
  
  @targetName("pipeTo")
  infix def |(cmd: Executable): Pipeline = cmd(this)

object Command:

  given AsMessage[Command] = command => Message(formattedArgs(command.args))

  private def formattedArgs(args: Seq[Text]): Text =
    args.map: arg =>
      if arg.contains(t"\"") && !arg.contains(t"'") then t"""'$arg'"""
      else if arg.contains(t"'") && !arg.contains(t"\"") then t""""$arg""""
      else if arg.contains(t"'") && arg.contains(t"\"")
        then t""""${arg.rsub(t"\\\"", t"\\\\\"")}""""
      else if arg.contains(t" ") || arg.contains(t"\t") || arg.contains(t"\\") then t"'$arg'"
      else arg
    .join(t" ")

  given Debug[Command] = cmd =>
    val cmdString: Text = formattedArgs(cmd.args)
    if cmdString.contains(t"\"") then t"sh\"\"\"$cmdString\"\"\"" else t"sh\"$cmdString\""

  given Display[Command] = cmd => out"${colors.LightSeaGreen}(${formattedArgs(cmd.args)})"

case class Command(args: Text*) extends Executable:
  def fork
      [ResultType]
      ()
      (using properties: SystemProperties, systemProperty: CanThrow[SystemPropertyError], log: Log)
      : Process[Exec, ResultType] =
    val processBuilder = ProcessBuilder(args.ss*)
    import fileApi.javaIo
    val dir = Properties.user.dir[ji.File]()
    
    processBuilder.directory(dir)
    
    val t0 = System.currentTimeMillis
    Log.info(out"Starting process ${this.out} in directory ${dir.getAbsolutePath.nn}")
    new Process(processBuilder.start().nn)

object Pipeline:
  inline given Debug[Pipeline] = new Debug[Pipeline]:
    def apply(pipeline: Pipeline): Text = pipeline.cmds.map(_.debug).join(t" | ")
  
  given Display[Pipeline] = _.cmds.map(_.out).join(out" ${colors.PowderBlue}(|) ")

case class Pipeline(cmds: Command*) extends Executable:
  def fork
      [ResultType]
      ()
      (using properties: SystemProperties, systemProperty: CanThrow[SystemPropertyError], log: Log)
      : Process[Exec, ResultType] =
    import fileApi.javaIo
    val dir = Properties.user.dir[ji.File]()
    
    Log.info(out"Starting pipelined processes ${this.out} in directory ${dir.getAbsolutePath.nn}")

    val processBuilders = cmds.map: cmd =>
      val pb = ProcessBuilder(cmd.args.ss*)
      pb.directory(dir)
      pb.nn

    new Process[Exec, ResultType](ProcessBuilder.startPipeline(processBuilders.asJava).nn.asScala.to(List).last)

case class ExecError(command: Command, stdout: LazyList[Bytes], stderr: LazyList[Bytes])
extends Error(msg"execution of the command $command failed")

object Sh:
  case class Params(params: Text*)

  object Prefix extends Interpolator[Params, State, Command]:
    import Context.*
  
    def complete(state: State): Command =
      val args = state.current match
        case Quotes2        => throw InterpolationError(msg"the double quotes have not been closed")
        case Quotes1        => throw InterpolationError(msg"the single quotes have not been closed")
        case _ if state.esc => throw InterpolationError(msg"cannot terminate with an escape character")
        case _              => state.args
      
      Command(args*)

    def initial: State = State(Awaiting, false, Nil)

    def skip(state: State): State = insert(state, Params(t"x"))

    def insert(state: State, value: Params): State =
      value.params.to(List) match
        case h :: t =>
          if state.esc then throw InterpolationError(msg"""
            escaping with '\\' is not allowed immediately before a substitution
          """)
          
          (state: @unchecked) match
            case State(Awaiting, false, args) =>
              State(Unquoted, false, args ++ (h :: t))

            case State(Unquoted, false, args :+ last) =>
              State(Unquoted, false, args ++ (t"$last$h" :: t))
            
            case State(Quotes1, false, args :+ last) =>
              State(Quotes1, false, args :+ (t"$last$h" :: t).join(t" "))
            
            case State(Quotes2, false, args :+ last) =>
              State(Quotes2, false, args :+ (t"$last$h" :: t).join(t" "))
            
        case _ =>
          state
          
    def parse(state: State, next: Text): State = next.chars.foldLeft(state): (state, next) =>
      ((state, next): @unchecked) match
        case (State(Awaiting, esc, args), ' ')          => State(Awaiting, false, args)
        case (State(Quotes1, false, rest :+ cur), '\\') => State(Quotes1, false, rest :+ t"$cur\\")
        case (State(ctx, false, args), '\\')            => State(ctx, true, args)
        case (State(Unquoted, esc, args), ' ')          => State(Awaiting, false, args)
        case (State(Quotes1, esc, args), '\'')          => State(Unquoted, false, args)
        case (State(Quotes2, false, args), '"')         => State(Unquoted, false, args)
        case (State(Unquoted, false, args), '"')        => State(Quotes2, false, args)
        case (State(Unquoted, false, args), '\'')       => State(Quotes1, false, args)
        case (State(Awaiting, false, args), '"')        => State(Quotes2, false, args :+ t"")
        case (State(Awaiting, false, args), '\'')       => State(Quotes1, false, args :+ t"")
        case (State(Awaiting, esc, args), char)         => State(Unquoted, false, args :+ t"$char")
        case (State(ctx, esc, Nil), char)               => State(ctx, false, List(t"$char"))
        case (State(ctx, esc, rest :+ cur), char)       => State(ctx, false, rest :+ t"$cur$char")

  given Insertion[Params, Text] = value => Params(value)
  given Insertion[Params, List[Text]] = xs => Params(xs*)
  given Insertion[Params, Command] = cmd => Params(cmd.args*)
  given [ValueType: AsParams]: Insertion[Params, ValueType] = value => Params(summon[AsParams[ValueType]].show(value))

object AsParams:
  given [PathType: GenericPathReader]: AsParams[PathType] = _.fullPath
  given AsParams[Int] = _.show
  
  given [ValueType](using encoder: Encoder[ValueType]): AsParams[ValueType]^{encoder} =
    new AsParams[ValueType]:
      def show(value: ValueType): Text = encoder.encode(value)

trait AsParams[-T]:
  def show(value: T): Text

given realm: Realm = Realm(t"guillotine")

object Guillotine:
  def sh(context: Expr[StringContext], parts: Expr[Seq[Any]])(using Quotes): Expr[Command] =
    import quotes.reflect.*
    
    val execType = ConstantType(StringConstant(context.value.get.parts.head.split(" ").nn.head.nn))
    val bounds = TypeBounds(execType, execType)

    (Refinement(TypeRepr.of[Command], "Exec", bounds).asType: @unchecked) match
      case '[type commandType <: Command; commandType] =>
        '{${Sh.Prefix.expand(context, parts)}.asInstanceOf[commandType]}
