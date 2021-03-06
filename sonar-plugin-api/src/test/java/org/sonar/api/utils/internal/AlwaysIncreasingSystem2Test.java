/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.api.utils.internal;

import javax.annotation.Nullable;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AlwaysIncreasingSystem2Test {


  @Test
  public void default_constructor_makes_now_start_with_random_number_and_increase_returned_value_by_100_with_each_call() {
    AlwaysIncreasingSystem2 underTest = new AlwaysIncreasingSystem2();
    verifyValuesReturnedByNow(underTest, null, 100);
  }

  @Test
  public void constructor_with_increment_makes_now_start_with_random_number_and_increase_returned_value_by_specified_value_with_each_call() {
    AlwaysIncreasingSystem2 underTest = new AlwaysIncreasingSystem2(663);

    verifyValuesReturnedByNow(underTest, null, 663);
  }

  @Test
  public void constructor_with_initial_value_and_increment_makes_now_start_with_specified_value_and_increase_returned_value_by_specified_value_with_each_call() {
    AlwaysIncreasingSystem2 underTest = new AlwaysIncreasingSystem2(777777L, 96);

    verifyValuesReturnedByNow(underTest, 777777L, 96);
  }

  private void verifyValuesReturnedByNow(AlwaysIncreasingSystem2 underTest, @Nullable Long initialValue, int increment) {
    long previousValue = -1;
    for (int i = 0; i < 333; i++) {
      if (previousValue == -1) {
        long now = underTest.now();
        if (initialValue != null) {
          assertThat(now).isEqualTo(initialValue);
        } else {
          assertThat(now).isGreaterThan(0);
        }
        previousValue = now;
      } else {
        long now = underTest.now();
        assertThat(now).isEqualTo(previousValue + increment);
        previousValue = now;
      }
    }
  }
}
