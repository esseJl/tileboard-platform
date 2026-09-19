package com.tileboard.engine.core;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public interface BoardContext {
    void publishBoard(Board<TileColor> board);

    void setTile(int row, int col, TileColor color);

    void fillBoard(TileColor color);

    Board<TileColor> newBoard();

    int boardWidth();

    int boardHeight();
}
