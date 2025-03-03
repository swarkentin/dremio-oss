/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { JobState } from '@app/utils/jobsUtils';

export const getNumberOfRunningJobs = (jobs) => {
  if (jobs) {
    return jobs.filter((item) => item.get('currentState') &&
      item.get('currentState').toLowerCase() === JobState.RUNNING).size;
  }
  return 0;
};

export const getDuration = (durationDetails, phase) => {
  const filteredDuration = durationDetails && durationDetails.find(duration => duration.get('phaseName') === phase);
  return filteredDuration ? Number(filteredDuration.get('phaseDuration')) / 1000 : 0;
};
