/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.runtime.internal

import androidx.compose.runtime.ComposeCompilerApi
import androidx.compose.runtime.ExperimentalComposeApi

/**
 * Used with decoys to stub the body of the decoy function.
 * Any call to this function at runtime indicates compiler error, as all calls to decoys
 * should be compiled out.
 *
 * @throws IllegalStateException
 */
@Suppress("unused")
@ComposeCompilerApi
fun illegalDecoyCallException(fName: String): Nothing =
    throw IllegalStateException(
        "Function $fName should have been replaced by compiler."
    )

/**
 * With decoys enabled, indicates original composable function that was stubbed by compiler
 * plugin. Provides metadata to link it with the implementation function
 * generated by compiler.
 *
 * @param targetName Name of the implementation function which this composable was copied to.
 * @param signature Serialized signature of the actual composable function.
 */
@Suppress("unused")
@ExperimentalComposeApi
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
annotation class Decoy(val targetName: String, vararg val signature: String)

/**
 * With decoys enabled, indicates composable implementation function created by compiler plugin.
 *
 * @param name Name of this implementation function.
 * @param id Id from original function signature to distinguish overloads.
 */
@Suppress("unused")
@ExperimentalComposeApi
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
annotation class DecoyImplementation(val name: String, val id: Long)

/**
 * Complements [DecoyImplementation] with extra information about default values for parameters.
 * Default values are erased and not present in klib.
 * This annotation uses [bitMask] to store the information about default values in original (decoy)
 * functions.
 *
 * @param bitMask keeps the flags of default values presence.
 */
@Suppress("unused")
@ExperimentalComposeApi
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
internal annotation class DecoyImplementationDefaultsBitMask(val bitMask: Int)