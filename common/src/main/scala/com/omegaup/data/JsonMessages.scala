package com.omegaup.data

import com.omegaup.libinteractive.idl.IDL
import com.omegaup.libinteractive.target.Options

case class NullMessage()

// from Runner
case class RunCaseResult(name: String, status: String, time: Int, memory: Int, output: Option[String] = None, context: Option[String] = None)
case class CaseData(name: String, data: String)

case class InteractiveDescription(idl: IDL, options: Options)
case class CompileInputMessage(lang: String, code: List[(String, String)], master_lang: Option[String] = None, master_code: Option[List[(String, String)]] = None, interactive: Option[InteractiveDescription] = None)
case class CompileOutputMessage(status: String = "ok", error: Option[String] = None, token: Option[String] = None)

case class RunInputMessage(token: String, timeLimit: Float = 1, memoryLimit: Int = 65535, outputLimit: Long = 10240, stackLimit: Long = 10485760, debug: Boolean = false, input: Option[String] = None, cases: Option[List[CaseData]] = None, interactive: Option[InteractiveDescription] = None)
case class RunOutputMessage(status: String = "ok", error: Option[String] = None, results: Option[List[RunCaseResult]] = None)

case class InputOutputMessage(status: String = "ok", error: Option[String] = None)

// from Grader
case class ReloadConfigInputMessage(overrides: Option[Map[String, String]] = None)
case class ReloadConfigOutputMessage(status: String = "ok", error: Option[String] = None)
case class StatusOutputMessage(status: String = "ok", embedded_runner: Boolean = true, queue: Option[QueueStatus] = None)
case class Running(name: String, id: Int)
case class QueueStatus(
  run_queue_length: Int,
  runner_queue_length: Int,
  runners: List[String],
  running: List[Running]
)
case class GradeInputMessage(id: Int, debug: Boolean = false, rejudge: Boolean = false)
case class GradeOutputMessage(status: String = "ok", error: Option[String] = None)
case class RegisterInputMessage(hostname: String, port: Int)
case class RegisterOutputMessage(status: String = "ok", error: Option[String] = None)

// for serializing judgement details
case class CaseVerdictMessage(name: String, verdict: String, score: Double)
case class GroupVerdictMessage(group: String, cases: List[CaseVerdictMessage], score: Double)

// Broadcaster
case class ContestRoleResponse(status: String = "ok", admin: Boolean = false)
case class BroadcastInputMessage(
  contest: String = "",
  message: String = "",
  broadcast: Boolean = false,
  targetUser: Long = -1,
  userOnly: Boolean = false
)
case class BroadcastOutputMessage(status: String = "ok", error: Option[String] = None)
case class ScoreboardRefreshResponse(status: String = "ok", error: Option[String] = None, errorcode: Option[String] = None, header: Option[String] = None)