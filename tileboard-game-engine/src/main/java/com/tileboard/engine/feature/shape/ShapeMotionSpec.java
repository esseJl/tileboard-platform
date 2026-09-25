package com.tileboard.engine.feature.shape;

import com.tileboard.engine.model.TileColor;

public final class ShapeMotionSpec {
    private final ShapeSpec shape;
    private final Trajectory trajectory;
    private final AnimationSpeed speed;
    private final int frames;
    private final boolean trail;
    private final TileColor trailColor;
    private final int trailLength;
    private final boolean clearAtEnd;
    private final int repeat;

    private ShapeMotionSpec(Builder b) {
        this.shape = b.shape;
        this.trajectory = b.trajectory;
        this.speed = b.speed;
        this.frames = b.frames > 0 ? b.frames : b.speed.suggestedFrames(Math.max(1, b.trajectory.lengthCells(30)));
        this.trail = b.trail;
        this.trailColor = b.trailColor;
        this.trailLength = b.trailLength;
        this.clearAtEnd = b.clearAtEnd;
        this.repeat = b.repeat;
    }

    public static Builder builder(ShapeSpec shape, Trajectory trajectory) {
        return new Builder(shape, trajectory);
    }

    public ShapeSpec shape() {
        return shape;
    }

    public Trajectory trajectory() {
        return trajectory;
    }

    public AnimationSpeed speed() {
        return speed;
    }

    public int frames() {
        return frames;
    }

    public boolean trail() {
        return trail;
    }

    public TileColor trailColor() {
        return trailColor;
    }

    public int trailLength() {
        return trailLength;
    }

    public boolean clearAtEnd() {
        return clearAtEnd;
    }

    public int repeat() {
        return repeat;
    }

    public static final class Builder {
        private final ShapeSpec shape;
        private final Trajectory trajectory;
        private AnimationSpeed speed = AnimationSpeed.NORMAL;
        private int frames = 0;
        private boolean trail = false;
        private TileColor trailColor;
        private int trailLength = 3;
        private boolean clearAtEnd = true;
        private int repeat = 1;

        private Builder(ShapeSpec shape, Trajectory trajectory) {
            this.shape = shape;
            this.trajectory = trajectory;
        }

        public Builder speed(AnimationSpeed speed) {
            this.speed = speed;
            return this;
        }

        public Builder frames(int frames) {
            this.frames = frames;
            return this;
        }

        public Builder withTrail(TileColor color, int length) {
            this.trail = true;
            this.trailColor = color;
            this.trailLength = length;
            return this;
        }

        public Builder clearAtEnd(boolean clear) {
            this.clearAtEnd = clear;
            return this;
        }

        public Builder repeat(int times) {
            this.repeat = times;
            return this;
        }

        public ShapeMotionSpec build() {
            return new ShapeMotionSpec(this);
        }
    }
}