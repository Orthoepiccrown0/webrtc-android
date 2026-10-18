# Resolved SDK dependency/license inventory

Generated from the five modules' release runtime graphs. Maven POM declarations are evidence for review, not legal clearance. Review upstream artifacts, bundled native WebRTC notices and transitive distribution obligations before public publishing. Original DirectStream source is licensed under Apache 2.0; third-party components retain their own licenses. Missing declarations require manual review. Android build tooling/test dependencies are outside this shipped-runtime inventory.

This is the union of five independently resolved graphs: multiple versions of a dependency can appear. The consuming application resolves its own final graph. Use [publication checks](PUBLICATION.md) for release gates.

Regenerate with `bash gradlew sdkDependencyInventory` then `python3 scripts/dependency_licenses.py`. Format the generated table with `worker/node_modules/.bin/prettier --write docs/DEPENDENCIES.md`.

| Resolved dependency                                                | Cached Maven license declaration                                                                            |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| `androidx.activity:activity-compose:1.12.4`                        | The Apache Software License, Version 2.0                                                                    |
| `androidx.activity:activity-ktx:1.12.4`                            | The Apache Software License, Version 2.0                                                                    |
| `androidx.activity:activity:1.0.0`                                 | Review upstream license (not present in cached POM)                                                         |
| `androidx.activity:activity:1.12.4`                                | The Apache Software License, Version 2.0                                                                    |
| `androidx.annotation:annotation-experimental:1.3.0`                | Review upstream license (not present in cached POM)                                                         |
| `androidx.annotation:annotation-experimental:1.4.1`                | The Apache Software License, Version 2.0                                                                    |
| `androidx.annotation:annotation-jvm:1.6.0`                         | Review upstream license (not present in cached POM)                                                         |
| `androidx.annotation:annotation-jvm:1.7.0`                         | Review upstream license (not present in cached POM)                                                         |
| `androidx.annotation:annotation-jvm:1.9.1`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.annotation:annotation:1.6.0`                             | Review upstream license (not present in cached POM)                                                         |
| `androidx.annotation:annotation:1.7.0`                             | Review upstream license (not present in cached POM)                                                         |
| `androidx.annotation:annotation:1.9.1`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.arch.core:core-common:2.1.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.arch.core:core-common:2.2.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.arch.core:core-runtime:2.1.0`                            | The Apache Software License, Version 2.0                                                                    |
| `androidx.arch.core:core-runtime:2.2.0`                            | The Apache Software License, Version 2.0                                                                    |
| `androidx.autofill:autofill:1.0.0`                                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.browser:browser:1.4.0`                                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.collection:collection-jvm:1.4.2`                         | Review upstream license (not present in cached POM)                                                         |
| `androidx.collection:collection-jvm:1.5.0`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.collection:collection-ktx:1.5.0`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.collection:collection:1.0.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.collection:collection:1.1.0`                             | Review upstream license (not present in cached POM)                                                         |
| `androidx.collection:collection:1.4.2`                             | Review upstream license (not present in cached POM)                                                         |
| `androidx.collection:collection:1.5.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.animation:animation-android:1.10.4`              | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.animation:animation-core-android:1.10.4`         | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.animation:animation-core:1.10.4`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.animation:animation:1.10.4`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.foundation:foundation-android:1.10.4`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.foundation:foundation-layout-android:1.10.4`     | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.foundation:foundation-layout:1.10.4`             | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.foundation:foundation:1.10.4`                    | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material3:material3-android:1.4.0`               | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material3:material3:1.4.0`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material:material-icons-core-android:1.7.8`      | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material:material-icons-core:1.7.8`              | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material:material-ripple-android:1.10.4`         | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.material:material-ripple:1.10.4`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-android:1.10.4`                  | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-annotation-android:1.10.4`       | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-annotation-android:1.9.0`        | Review upstream license (not present in cached POM)                                                         |
| `androidx.compose.runtime:runtime-annotation:1.10.4`               | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-annotation:1.9.0`                | Review upstream license (not present in cached POM)                                                         |
| `androidx.compose.runtime:runtime-retain-android:1.10.4`           | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-retain:1.10.4`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-saveable-android:1.10.4`         | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime-saveable:1.10.4`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.runtime:runtime:1.10.4`                          | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-android:1.10.4`                            | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-geometry-android:1.10.4`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-geometry:1.10.4`                           | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-graphics-android:1.10.4`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-graphics:1.10.4`                           | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-text-android:1.10.4`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-text:1.10.4`                               | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-tooling-preview-android:1.10.4`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-tooling-preview:1.10.4`                    | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-unit-android:1.10.4`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-unit:1.10.4`                               | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-util-android:1.10.4`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui-util:1.10.4`                               | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose.ui:ui:1.10.4`                                    | The Apache Software License, Version 2.0                                                                    |
| `androidx.compose:compose-bom:2026.02.01`                          | The Apache Software License, Version 2.0                                                                    |
| `androidx.concurrent:concurrent-futures:1.0.0`                     | Review upstream license (not present in cached POM)                                                         |
| `androidx.concurrent:concurrent-futures:1.1.0`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.core:core-ktx:1.10.1`                                    | Review upstream license (not present in cached POM)                                                         |
| `androidx.core:core-ktx:1.16.0`                                    | The Apache Software License, Version 2.0                                                                    |
| `androidx.core:core-telecom:1.0.0`                                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.core:core-viewtree:1.0.0`                                | The Apache Software License, Version 2.0                                                                    |
| `androidx.core:core:1.10.1`                                        | Review upstream license (not present in cached POM)                                                         |
| `androidx.core:core:1.16.0`                                        | The Apache Software License, Version 2.0                                                                    |
| `androidx.credentials:credentials-play-services-auth:1.2.0-rc01`   | The Apache Software License, Version 2.0                                                                    |
| `androidx.credentials:credentials:1.2.0-rc01`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.customview:customview-poolingcontainer:1.0.0`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.customview:customview:1.0.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-android:1.1.7`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-core-android:1.1.7`                  | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-core-okio-jvm:1.1.7`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-core-okio:1.1.7`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-core:1.1.7`                          | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-preferences-android:1.1.7`           | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-preferences-core-android:1.1.7`      | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-preferences-core:1.1.7`              | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-preferences-external-protobuf:1.1.7` | [BSD-3-Clause](https://opensource.org/licenses/BSD-3-Clause)                                                |
| `androidx.datastore:datastore-preferences-proto:1.1.7`             | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore-preferences:1.1.7`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.datastore:datastore:1.1.7`                               | The Apache Software License, Version 2.0                                                                    |
| `androidx.documentfile:documentfile:1.0.0`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.dynamicanimation:dynamicanimation:1.0.0`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.emoji2:emoji2:1.4.0`                                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.fragment:fragment:1.1.0`                                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.graphics:graphics-path:1.0.1`                            | The Apache Software License, Version 2.0                                                                    |
| `androidx.interpolator:interpolator:1.0.0`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.legacy:legacy-support-core-utils:1.0.0`                  | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-common-java8:2.10.0`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-common-jvm:2.10.0`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-common:2.10.0`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-common:2.3.1`                        | Review upstream license (not present in cached POM)                                                         |
| `androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-livedata-core:2.0.0`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-livedata-core:2.10.0`                | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-livedata:2.0.0`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-livedata:2.10.0`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-process:2.10.0`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime-android:2.10.0`              | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0`      | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime-compose:2.10.0`              | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0`          | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.10.0`                  | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime:2.10.0`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-runtime:2.3.1`                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-service:2.10.0`                      | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.10.0`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.10.0`    | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0`            | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0`                | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.10.0` | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0`         | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel:2.1.0`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.lifecycle:lifecycle-viewmodel:2.10.0`                    | The Apache Software License, Version 2.0                                                                    |
| `androidx.loader:loader:1.0.0`                                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0`       | The Apache Software License, Version 2.0                                                                    |
| `androidx.navigationevent:navigationevent-android:1.0.2`           | The Apache Software License, Version 2.0                                                                    |
| `androidx.navigationevent:navigationevent-compose-android:1.0.2`   | The Apache Software License, Version 2.0                                                                    |
| `androidx.navigationevent:navigationevent-compose:1.0.2`           | The Apache Software License, Version 2.0                                                                    |
| `androidx.navigationevent:navigationevent:1.0.2`                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.print:print:1.0.0`                                       | The Apache Software License, Version 2.0                                                                    |
| `androidx.profileinstaller:profileinstaller:1.4.0`                 | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate-android:1.4.0`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate-compose-android:1.4.0`             | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate-compose:1.4.0`                     | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate-ktx:1.4.0`                         | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate:1.0.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.savedstate:savedstate:1.4.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.startup:startup-runtime:1.1.1`                           | The Apache Software License, Version 2.0                                                                    |
| `androidx.tracing:tracing:1.2.0`                                   | The Apache Software License, Version 2.0                                                                    |
| `androidx.transition:transition:1.6.0`                             | The Apache Software License, Version 2.0                                                                    |
| `androidx.versionedparcelable:versionedparcelable:1.1.1`           | The Apache Software License, Version 2.0                                                                    |
| `androidx.viewpager:viewpager:1.0.0`                               | The Apache Software License, Version 2.0                                                                    |
| `androidx.window:window-core-android:1.5.0`                        | The Apache Software License, Version 2.0                                                                    |
| `androidx.window:window-core:1.5.0`                                | The Apache Software License, Version 2.0                                                                    |
| `androidx.window:window:1.5.0`                                     | The Apache Software License, Version 2.0                                                                    |
| `com.google.android.datatransport:transport-api:3.1.0`             | The Apache Software License, Version 2.0                                                                    |
| `com.google.android.datatransport:transport-backend-cct:3.1.9`     | The Apache Software License, Version 2.0                                                                    |
| `com.google.android.datatransport:transport-runtime:3.1.9`         | The Apache Software License, Version 2.0                                                                    |
| `com.google.android.gms:play-services-auth-api-phone:18.0.2`       | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-auth-base:18.0.4`            | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-auth:20.7.0`                 | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-base:18.1.0`                 | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-basement:18.4.0`             | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-cloud-messaging:17.2.0`      | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-fido:20.1.0`                 | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-stats:17.0.2`                | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.gms:play-services-tasks:18.2.0`                | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.libraries.identity.googleid:googleid:1.1.0`    | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.android.play:core-common:2.0.3`                        | [Play Core Software Development Kit Terms of Service](https://developer.android.com/guide/playcore/license) |
| `com.google.android.play:integrity:1.3.0`                          | [Play Integrity API Terms of Service](https://developer.android.com/google/play/integrity/overview#tos)     |
| `com.google.android.recaptcha:recaptcha:18.6.1`                    | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.errorprone:error_prone_annotations:2.26.0`             | Apache 2.0                                                                                                  |
| `com.google.firebase:firebase-annotations:17.0.0`                  | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-appcheck-interop:17.1.0`             | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-auth-interop:20.0.0`                 | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.firebase:firebase-auth:24.0.1`                         | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.firebase:firebase-bom:34.7.0`                          | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-common:22.0.1`                       | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-components:19.0.0`                   | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-database-collection:18.0.1`          | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-database:22.0.1`                     | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-datatransport:18.2.0`                | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-encoders-json:18.0.0`                | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-encoders-proto:16.0.0`               | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-encoders:17.0.0`                     | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-iid-interop:17.1.0`                  | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.firebase:firebase-installations-interop:17.1.1`        | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-installations:19.0.1`                | The Apache Software License, Version 2.0                                                                    |
| `com.google.firebase:firebase-measurement-connector:19.0.0`        | [Android Software Development Kit License](https://developer.android.com/studio/terms.html)                 |
| `com.google.firebase:firebase-messaging:25.0.1`                    | The Apache Software License, Version 2.0                                                                    |
| `com.google.guava:listenablefuture:1.0`                            | Review upstream license (not present in cached POM)                                                         |
| `com.jakewharton.timber:timber:5.0.1`                              | The Apache Software License, Version 2.0                                                                    |
| `com.squareup.okhttp3:okhttp:4.12.0`                               | The Apache Software License, Version 2.0                                                                    |
| `com.squareup.okio:okio-jvm:3.6.0`                                 | The Apache Software License, Version 2.0                                                                    |
| `com.squareup.okio:okio:3.6.0`                                     | The Apache Software License, Version 2.0                                                                    |
| `io.getstream:stream-webrtc-android:1.3.9`                         | The Apache Software License, Version 2.0                                                                    |
| `javax.inject:javax.inject:1`                                      | The Apache Software License, Version 2.0                                                                    |
| `org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22`    | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22`             | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlin:kotlin-stdlib-common:2.2.10`                 | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10`                   | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10`                   | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.10`                        | The Apache License, Version 2.0                                                                             |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`          | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2`              | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2`         | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`             | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2`    | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom:1.9.0`            | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0`       | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0`           | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0`       | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`           | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                                               |
| `org.jetbrains:annotations:23.0.0`                                 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)                 |
| `org.jspecify:jspecify:1.0.0`                                      | The Apache License, Version 2.0                                                                             |
