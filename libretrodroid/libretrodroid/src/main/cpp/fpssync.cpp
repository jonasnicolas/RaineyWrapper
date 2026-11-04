/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <algorithm>
#include <cmath>
#include "fpssync.h"
#include "log.h"

namespace libretrodroid {

unsigned FPSSync::advanceFrames() {
    if (useVSync) return 1;

    if (lastFrame == MIN_TIME) {
        start();
    }

    auto now = std::chrono::steady_clock::now();
    auto frames = std::max((now - lastFrame) / sampleInterval, (long long) 1);
    lastFrame = lastFrame + sampleInterval * frames;

    return frames;
}

FPSSync::FPSSync(double contentRefreshRate, double screenRefreshRate) {
    this->contentRefreshRate = contentRefreshRate;
    this->screenRefreshRate = screenRefreshRate;
    this->useVSync = std::abs(contentRefreshRate - screenRefreshRate) < FPS_TOLERANCE;
    this->sampleInterval = std::chrono::microseconds((long) ((1000000L / contentRefreshRate)));
    this->limitInterval = std::chrono::microseconds((long) (1000000L / std::max(contentRefreshRate, screenRefreshRate)));
    reset();
}

void FPSSync::start() {
    LOGI("Starting game with fps %f on a screen with refresh rate %f. Using vsync: %d", contentRefreshRate, screenRefreshRate, useVSync);
    lastFrame = std::chrono::steady_clock::now();
}

void FPSSync::reset() {
    lastFrame = MIN_TIME;
    limitTarget = MIN_TIME;
}

double FPSSync::getTimeStretchFactor() {
    return useVSync ? contentRefreshRate / screenRefreshRate : 1.0;
}

void FPSSync::wait() {
    if (useVSync) {
        // RaineyWrapper: vsync only sets the right speed if the screen keeps refreshing at the rate
        // it reported when the game started. Phones and handhelds switch between 60, 90 and 120 Hz,
        // and some cores' frames don't block on vsync at all, which made games run too fast. So also
        // never start a frame sooner than one game frame after the last (or one screen refresh,
        // whichever is shorter). When vsync paces correctly, this never waits.
        auto now = std::chrono::steady_clock::now();
        if (limitTarget != MIN_TIME && now < limitTarget) {
            std::this_thread::sleep_until(limitTarget);
            now = limitTarget;
        }
        limitTarget = now + limitInterval;
        return;
    }
    std::this_thread::sleep_until(lastFrame);
}

} //namespace libretrodroid
