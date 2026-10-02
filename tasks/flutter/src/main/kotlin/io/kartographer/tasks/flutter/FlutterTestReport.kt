package io.kartographer.tasks.flutter

import io.kartographer.tasks.dart.DartTestCaseResult
import io.kartographer.tasks.dart.DartTestError
import io.kartographer.tasks.dart.DartTestReportParser
import io.kartographer.tasks.dart.DartTestRunSummary

typealias FlutterTestError = DartTestError
typealias FlutterTestCaseResult = DartTestCaseResult
typealias FlutterTestRunSummary = DartTestRunSummary
typealias LcovCoverageSummary = io.kartographer.tasks.dart.LcovCoverageSummary
typealias LcovFileCoverage = io.kartographer.tasks.dart.LcovFileCoverage
typealias FlutterTestReportParser = DartTestReportParser

// A typealias does not carry the aliased object's nested types, and the report generators
// take these directly.
typealias TestSuiteReport = DartTestReportParser.SuiteReport
typealias FlutterCoverageSort = DartTestReportParser.CoverageSort
