/*
    Guillotine, version [unreleased]. Copyright 2024 Jon Pretty, Propensive OÜ.

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

import language.experimental.pureFunctions

import java.io as ji

import scala.jdk.StreamConverters.StreamHasToScala

import anticipation.*
import contingency.*
import gossamer.*
import rudiments.*
import spectacular.*
import turbulence.*

object Computable:
  given LazyList[Text] is Computable as lazyList = proc =>
    val reader = ji.BufferedReader(ji.InputStreamReader(proc.getInputStream))
    reader.lines().nn.toScala(LazyList).map(_.tt)

  given List[Text] is Computable as list = proc =>
    val reader = ji.BufferedReader(ji.InputStreamReader(proc.getInputStream))
    reader.lines().nn.toScala(List).map(_.tt)

  given Text is Computable as text = proc =>
    Text.construct(lazyList.compute(proc).map(_.s).each(append(_)))

  given String is Computable as string = proc =>
    Text.construct(lazyList.compute(proc).map(_.s).each(append(_))).s

  given (using streamCut: Tactic[StreamError]) => LazyList[Bytes] is Computable as dataStream =
    proc => Readable.inputStream.stream(proc.getInputStream.nn)

  given ExitStatus is Computable as exitStatus = _.waitFor() match
    case 0     => ExitStatus.Ok
    case other => ExitStatus.Fail(other)

  given Unit is Computable = exitStatus.map(_ => ())

  given [PathType: SpecificPath] => PathType is Computable =
    proc => SpecificPath(text.compute(proc))

@capability
trait Computable:
  type Self
  def compute(process: java.lang.Process): Self

  def map[SelfType2](lambda: Self => SelfType2): SelfType2 is Computable =
    process => lambda(compute(process))
