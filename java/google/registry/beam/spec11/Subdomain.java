// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.beam.spec11;

import static google.registry.beam.BeamUtils.checkFieldsNotNull;
import static google.registry.beam.BeamUtils.extractField;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import java.io.Serializable;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;

/**
 * A POJO representing a single subdomain, parsed from a {@code SchemaAndRecord}.
 *
 * <p>This is a trivially serializable class that allows Beam to transform the results of a Bigquery
 * query into a standard Java representation, giving us the type guarantees and ease of manipulation
 * Bigquery lacks, while localizing any Bigquery-side failures to the {@link #parseFromRecord}
 * function.
 */
@AutoValue
public abstract class Subdomain implements Serializable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableList<String> FIELD_NAMES =
      ImmutableList.of("fullyQualifiedDomainName", "registrarName", "registrarEmailAddress");

  /** Returns the fully qualified domain name. */
  abstract String fullyQualifiedDomainName();
  /** Returns the name of the associated registrar for this domain. */
  abstract String registrarName();
  /** Returns the email address of the registrar associated with this domain. */
  abstract String registrarEmailAddress();

  /**
   * Constructs a {@link Subdomain} from an Apache Avro {@code SchemaAndRecord}.
   *
   * @see <a
   *     href=http://avro.apache.org/docs/1.7.7/api/java/org/apache/avro/generic/GenericData.Record.html>
   *     Apache AVRO GenericRecord</a>
   */
  static Subdomain parseFromRecord(SchemaAndRecord schemaAndRecord) {
    checkFieldsNotNull(FIELD_NAMES, schemaAndRecord);
    GenericRecord record = schemaAndRecord.getRecord();
    return create(
        extractField(record, "fullyQualifiedDomainName"),
        extractField(record, "registrarName"),
        extractField(record, "registrarEmailAddress"));
  }

  /**
   * Creates a concrete {@link Subdomain}.
   *
   * <p>This should only be used outside this class for testing- instances of {@link Subdomain}
   * should otherwise come from {@link #parseFromRecord}.
   */
  @VisibleForTesting
  static Subdomain create(
      String fullyQualifiedDomainName, String registrarName, String registrarEmailAddress) {
    return new AutoValue_Subdomain(fullyQualifiedDomainName, registrarName, registrarEmailAddress);
  }
}

