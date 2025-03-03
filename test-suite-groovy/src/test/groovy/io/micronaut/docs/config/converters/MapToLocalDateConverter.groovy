/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.docs.config.converters

import io.micronaut.context.annotation.Prototype
import io.micronaut.core.convert.ConversionContext

// tag::imports[]

import io.micronaut.core.convert.ConversionService
import io.micronaut.core.convert.TypeConverter

import java.time.DateTimeException
import java.time.LocalDate
// end::imports[]

// tag::class[]
@Prototype
class MapToLocalDateConverter implements TypeConverter<Map, LocalDate> { // <1>
    @Override
    Optional<LocalDate> convert(Map propertyMap, Class<LocalDate> targetType, ConversionContext context) {
        Optional<Integer> day = ConversionService.SHARED.convert(propertyMap.day, Integer)
        Optional<Integer> month = ConversionService.SHARED.convert(propertyMap.month, Integer)
        Optional<Integer> year = ConversionService.SHARED.convert(propertyMap.year, Integer)
        if (day.present && month.present && year.present) {
            try {
                return Optional.of(LocalDate.of(year.get(), month.get(), day.get())) // <2>
            } catch (DateTimeException e) {
                context.reject(propertyMap, e) // <3>
                return Optional.empty()
            }
        }
        return Optional.empty()
    }
}
// end::class[]
