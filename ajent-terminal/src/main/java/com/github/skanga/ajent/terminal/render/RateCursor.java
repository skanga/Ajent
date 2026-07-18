package com.github.skanga.ajent.terminal.render;

/** Rate-smoothed bounded-lag typewriter cursor with a hard finalize ramp. */
public final class RateCursor {
  private double position;
  private double floorRate;
  private double drainSeconds;
  private double rampLeft = -1;
  private double smoothedRate = -1;
  private double rateTau = 0.15;
  private boolean wasRamping;
  private double maxBurstMultiplier = 1.8;

  public RateCursor() {
    this(30, 0.25);
  }

  public RateCursor(double floorRate, double drainSeconds) {
    this.floorRate = floorRate > 0 ? floorRate : 1;
    this.drainSeconds = drainSeconds > 0 ? drainSeconds : 0.001;
  }

  public void setPacing(double newFloorRate, double newDrainSeconds) {
    if (newFloorRate > 0) floorRate = newFloorRate;
    if (newDrainSeconds > 0) drainSeconds = newDrainSeconds;
  }

  public void setSmoothing(double newRateTau, double newMaxBurstMultiplier) {
    if (newRateTau >= 0) rateTau = newRateTau;
    if (newMaxBurstMultiplier > 0) maxBurstMultiplier = newMaxBurstMultiplier;
  }

  public void setPosition(double value) { position = value < 0 ? 0 : value; }
  public double position() { return position; }

  public void advanceFloor(double floor) {
    if (position < floor) position = floor;
  }

  public void setDeadline(double seconds) { rampLeft = seconds; }
  public void clearDeadline() { rampLeft = -1; }
  public boolean ramping() { return rampLeft >= 0; }

  public double tick(double target, double elapsedSeconds) {
    double elapsed = Math.max(0, elapsedSeconds);
    double backlog = target - position;
    if (backlog <= 0) {
      position = target;
      if (rampLeft >= 0) rampLeft -= elapsed;
      wasRamping = rampLeft >= 0;
      return position;
    }

    double rateTarget = Math.max(floorRate, backlog / drainSeconds);
    if (smoothedRate < 0) {
      smoothedRate = floorRate;
    } else if (rateTau > 0) {
      double alpha = elapsed / (elapsed + rateTau);
      smoothedRate += (rateTarget - smoothedRate) * alpha;
    } else {
      smoothedRate = rateTarget;
    }
    if (smoothedRate < floorRate) smoothedRate = floorRate;
    double rate = smoothedRate;

    if (rampLeft >= 0) {
      if (rampLeft <= 0) rate = backlog / (elapsed > 0.001 ? elapsed : 0.001);
      else rate = Math.max(rate, backlog / rampLeft);
      rampLeft -= elapsed;
      if (!wasRamping) smoothedRate = rate;
      wasRamping = true;
    } else {
      wasRamping = false;
    }

    double maximumRate = backlog / (elapsed > 1e-9 ? elapsed : 1e-9);
    if (rate > maximumRate) rate = maximumRate;
    position += rate * elapsed;
    if (position > target) position = target;
    return position;
  }

  public void reset() {
    position = 0;
    rampLeft = -1;
    smoothedRate = -1;
    wasRamping = false;
  }
}
