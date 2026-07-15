package mse.quill.model;

import android.graphics.PointF;

import java.util.List;

public class Stroke {
    public String id;
    public String whiteboardId;
    public String authorId;
    public int tool;        // 0=pen, 1=eraser, 2=highlighter
    public int color;
    public float width;
    public List<PointF> points;
    public long createdAt;
}
