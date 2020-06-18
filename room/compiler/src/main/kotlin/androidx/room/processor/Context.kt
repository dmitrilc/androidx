/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.room.processor

import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.ext.hasAnnotation
import androidx.room.log.RLog
import androidx.room.parser.expansion.ProjectionExpander
import androidx.room.parser.optimization.RemoveUnusedColumnQueryRewriter
import androidx.room.preconditions.Checks
import androidx.room.processor.cache.Cache
import androidx.room.solver.TypeAdapterStore
import androidx.room.verifier.DatabaseVerifier
import androidx.room.vo.Warning
import java.io.File
import java.util.LinkedHashSet
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.type.TypeMirror

class Context private constructor(
    val processingEnv: ProcessingEnvironment,
    val logger: RLog,
    private val typeConverters: CustomConverterProcessor.ProcessResult,
    private val inheritedAdapterStore: TypeAdapterStore?,
    val cache: Cache,
    private val canRewriteQueriesToDropUnusedColumns: Boolean
) {
    val checker: Checks = Checks(logger)
    val COMMON_TYPES: Context.CommonTypes = Context.CommonTypes(processingEnv)

    val typeAdapterStore by lazy {
        if (inheritedAdapterStore != null) {
            TypeAdapterStore.copy(this, inheritedAdapterStore)
        } else {
            TypeAdapterStore.create(this, typeConverters.converters)
        }
    }

    // set when database and its entities are processed.
    var databaseVerifier: DatabaseVerifier? = null
        private set

    val queryRewriter: QueryRewriter by lazy {
        val verifier = databaseVerifier
        if (verifier == null) {
            QueryRewriter.NoOpRewriter
        } else {
            if (canRewriteQueriesToDropUnusedColumns) {
                RemoveUnusedColumnQueryRewriter
            } else if (BooleanProcessorOptions.EXPAND_PROJECTION.getValue(processingEnv)) {
                ProjectionExpander(
                    tables = verifier.entitiesAndViews
                )
            } else {
                QueryRewriter.NoOpRewriter
            }
        }
    }

    companion object {
        val ARG_OPTIONS by lazy {
            ProcessorOptions.values().map { it.argName } +
                    BooleanProcessorOptions.values().map { it.argName }
        }
    }

    fun attachDatabaseVerifier(databaseVerifier: DatabaseVerifier) {
        check(this.databaseVerifier == null) {
            "database verifier is already set"
        }
        this.databaseVerifier = databaseVerifier
    }

    constructor(processingEnv: ProcessingEnvironment) : this(
            processingEnv = processingEnv,
            logger = RLog(RLog.ProcessingEnvMessager(processingEnv), emptySet(), null),
            typeConverters = CustomConverterProcessor.ProcessResult.EMPTY,
            inheritedAdapterStore = null,
            cache = Cache(null, LinkedHashSet(), emptySet()),
            canRewriteQueriesToDropUnusedColumns = false)

    class CommonTypes(val processingEnv: ProcessingEnvironment) {
        val VOID: TypeMirror by lazy {
            processingEnv.elementUtils.getTypeElement("java.lang.Void").asType()
        }
        val STRING: TypeMirror by lazy {
            processingEnv.elementUtils.getTypeElement("java.lang.String").asType()
        }
        val COLLECTION: TypeMirror by lazy {
            processingEnv.elementUtils.getTypeElement("java.util.Collection").asType()
        }
    }

    val schemaOutFolder by lazy {
        val arg = processingEnv.options[ProcessorOptions.OPTION_SCHEMA_FOLDER.argName]
        if (arg?.isNotEmpty() ?: false) {
            File(arg)
        } else {
            null
        }
    }

    fun <T> collectLogs(handler: (Context) -> T): Pair<T, RLog.CollectingMessager> {
        val collector = RLog.CollectingMessager()
        val subContext = Context(processingEnv = processingEnv,
                logger = RLog(collector, logger.suppressedWarnings, logger.defaultElement),
                typeConverters = this.typeConverters,
                inheritedAdapterStore = typeAdapterStore,
                cache = cache,
                canRewriteQueriesToDropUnusedColumns = canRewriteQueriesToDropUnusedColumns)
        subContext.databaseVerifier = databaseVerifier
        val result = handler(subContext)
        return Pair(result, collector)
    }

    fun fork(element: Element, forceSuppressedWarnings: Set<Warning> = emptySet()): Context {
        val suppressedWarnings = SuppressWarningProcessor.getSuppressedWarnings(element)
        val processConvertersResult = CustomConverterProcessor.findConverters(this, element)
        val canReUseAdapterStore = processConvertersResult.classes.isEmpty()
        // order here is important since the sub context should give priority to new converters.
        val subTypeConverters = if (canReUseAdapterStore) {
            this.typeConverters
        } else {
            processConvertersResult + this.typeConverters
        }
        val subSuppressedWarnings =
            forceSuppressedWarnings + suppressedWarnings + logger.suppressedWarnings
        val subCache = Cache(cache, subTypeConverters.classes, subSuppressedWarnings)
        val subCanRemoveUnusedColumns = canRewriteQueriesToDropUnusedColumns ||
                element.hasRemoveUnusedColumnsAnnotation()
        val subContext = Context(
                processingEnv = processingEnv,
                logger = RLog(logger.messager, subSuppressedWarnings, element),
                typeConverters = subTypeConverters,
                inheritedAdapterStore = if (canReUseAdapterStore) typeAdapterStore else null,
                cache = subCache,
                canRewriteQueriesToDropUnusedColumns = subCanRemoveUnusedColumns)
        subContext.databaseVerifier = databaseVerifier
        return subContext
    }

    private fun Element.hasRemoveUnusedColumnsAnnotation(): Boolean {
        return hasAnnotation(RewriteQueriesToDropUnusedColumns::class).also { annotated ->
            if (annotated && BooleanProcessorOptions.EXPAND_PROJECTION.getValue(processingEnv)) {
                logger.w(
                    warning = Warning.EXPAND_PROJECTION_WITH_REMOVE_UNUSED_COLUMNS,
                    element = this,
                    msg = ProcessorErrors.EXPAND_PROJECTION_ALONG_WITH_REMOVE_UNUSED
                )
            }
        }
    }

    enum class ProcessorOptions(val argName: String) {
        OPTION_SCHEMA_FOLDER("room.schemaLocation")
    }

    enum class BooleanProcessorOptions(val argName: String, private val defaultValue: Boolean) {
        INCREMENTAL("room.incremental", true),
        EXPAND_PROJECTION("room.expandProjection", false);

        /**
         * Returns the value of this option passed through the [ProcessingEnvironment]. If the value
         * is null or blank, it returns the default value instead.
         */
        fun getValue(processingEnv: ProcessingEnvironment): Boolean {
            val value = processingEnv.options[argName]
            return if (value.isNullOrBlank()) {
                defaultValue
            } else {
                value.toBoolean()
            }
        }
    }
}
