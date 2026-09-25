package com.tileboard.engine.feature.shape;

import com.tileboard.engine.feature.BoardAnimation;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Position;

/**
 * Ready-made shape motions tuned for fast-paced "jump" tile games.
 */
public final class JumpGameAnimations {
    private JumpGameAnimations() {
    }

    /**
     * Player leaping from one tile to another.
     */
    public static ShapeMotionSpec playerJump(Position from, Position to, TileColor color, AnimationSpeed speed) {
        ShapeSpec player = ShapeSpec.circle(1, ShapeStyle.FILLED, color);
        return ShapeMotionSpec.builder(player, Trajectories.jumpArc(from, to, 3))
                .speed(speed)
                .clearAtEnd(false) // keep the player lit on landing
                .build();
    }

    /**
     * An obstacle/tile dropping down a column.
     */
    public static ShapeMotionSpec obstacleFall(int column, int fromRow, int toRow, TileColor color, AnimationSpeed speed) {
        ShapeSpec block = ShapeSpec.square(1, ShapeStyle.FILLED, color);
        return ShapeMotionSpec.builder(block, Trajectories.linear(new Position(fromRow, column), new Position(toRow, column)))
                .speed(speed)
                .build();
    }

    /**
     * A fast diagonal streak with a fading trail — good for "combo"/"power-up" feedback.
     */
    public static ShapeMotionSpec meteor(Position from, Position to, TileColor headColor, TileColor trailColor, AnimationSpeed speed) {
        ShapeSpec head = ShapeSpec.diamond(1, ShapeStyle.FILLED, headColor);
        return ShapeMotionSpec.builder(head, Trajectories.linear(from, to))
                .speed(speed)
                .withTrail(trailColor, 4)
                .build();
    }

    /**
     * Quick expanding ring when a tile is physically touched.
     */
    public static BoardAnimation touchRipple(Position touchedTile, TileColor color) {
        return new ShapePulseAnimation(touchedTile, ShapeType.CIRCLE, ShapeStyle.OUTLINE, color, 0, 3, 1, 60, false);
    }

    /**
     * Diamond outline pulsing twice — combo achieved on a tile.
     */
    public static BoardAnimation comboFlash(Position center, TileColor color) {
        return new ShapePulseAnimation(center, ShapeType.DIAMOND, ShapeStyle.OUTLINE, color, 0, 2, 2, 80, true);
    }

    /**
     * Growing red square outline — "incoming obstacle" warning before it lands.
     */
    public static BoardAnimation dangerPulse(Position center, int maxRadius) {
        return new ShapePulseAnimation(center, ShapeType.SQUARE, ShapeStyle.OUTLINE, TileColor.RED, 1, maxRadius, 3, 150, true);
    }
}