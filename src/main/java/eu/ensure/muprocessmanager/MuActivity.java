/*
 * Copyright (C) 2017 Frode Randers
 * All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package eu.ensure.muprocessmanager;

/**
 * A representation of any activity taking place in a {@link MuProcess} that has
 * both a forward as well as a backward (compensational) behaviour.
 * <p>
 * The idea is to wrap both the work to do along the "happy path" (walking forward)
 * as well as the compensation (walking backward).
 * <p>
 * Be sure to implement the default constructor, because the compensation is logged
 * to database ({@link MuPersistentLog} takes care of this) from which a MuActivity is
 * instantiated dynamically upon which the compensation is invoked.
 */
public interface MuActivity extends MuForwardBehaviour, MuBackwardBehaviour {
}
