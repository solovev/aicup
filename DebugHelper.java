import model.LivingUnit;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author Nha
 */
public final class DebugHelper
{
    private static Map<String, Object> textData;

    private static Socket socket;
    private static OutputStream outputStream;
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 13579;

    public static void initialize()
    {
        if (socket != null)
            return;

        Locale.setDefault(new Locale("en", "US"));
        try
        {

            textData = new LinkedHashMap<>();


            socket = new Socket(DEFAULT_HOST, DEFAULT_PORT);
            outputStream = socket.getOutputStream();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private static void sendCommand(String command)
    {
        try
        {
            outputStream.write((command + System.lineSeparator()).getBytes());
            outputStream.flush();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * start queueing commands to be displayed either before main drawing
     */
    public static void beginPre()
    {
        sendCommand("begin pre");
    }

    /**
     * start queueing commands to be displayed either after main drawing
     */
    public static void beginPost()
    {
        sendCommand("begin post");
    }

    /**
     * mark either "pre" queue of commands as ready to be displayed
     */
    public static void endPre()
    {
        sendCommand("end pre");
    }

    /**
     * mark either "post" queue of commands as ready to be displayed
     */
    public static void endPost()
    {
        sendCommand("end post");
    }

    /**
     * draw a circle at (x, y) with radius r and color color
     */
    public static void circle(double x, double y, double r, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("circle %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x, y, r, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    /**
     * draw a filled circle at (x, y) with radius r and color color
     */
    public static void fillCircle(double x, double y, double r, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("fill_circle %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x, y, r, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    /**
     * draw a rect with corners at (x, y) to (x, y) with color color
     */
    public static void rect(double x1, double y1, double x2, double y2, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("rect %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    /**
     * draw a filled rect with corners at (x1, y1) to (x2, y2) with color color
     */
    public static void fillRect(double x1, double y1, double x2, double y2, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("fill_rect %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public static void pathTo(LivingUnit self, LivingUnit unit, Color color)
    {
        if (unit == null)
            return;;

        circle(unit.getX(), unit.getY(), unit.getRadius() + 1, color);
        line(self.getX(), self.getY(), unit.getX(), unit.getY(), color);
    }

    /**
     * draw a line from (x1, y1) to (x2, y2) with color color
     */
    public static void line(double x1, double y1, double x2, double y2, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("line %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f %1.1f", x1, y1, x2, y2, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    /**
     * show msg at coordinates (x, y) with color color
     */
    public static void text(double x, double y, String msg, Color color)
    {
        Formatter f = new Formatter();
        sendCommand(f.format("text %1.1f %1.1f %s %1.1f %1.1f %1.1f", x, y, msg, (float) color.getRed() / 255, (float) color.getGreen() / 255, (float) color.getBlue() / 255).toString());
    }

    public static void addLabel(String label, Object data)
    {
        textData.put(label, data);
    }

    public static void sendLabels()
    {
        int i = 0;
        for (Map.Entry<String, Object> e : textData.entrySet())
        {
            text(25, 120 + i * 10, e.getKey() + ": " + e.getValue().toString(), Color.BLACK);
            i++;
        }
    }

    public static void stop()
    {
        try
        {
            outputStream.close();
            socket.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}