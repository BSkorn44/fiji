/* Copyright 2012 Tiago Ferreira, 2005 Tom Maddock
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.*;
import ij.measure.Calibration;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.process.*;
import ij.text.*;
import ij.util.Tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Rectangle;
import java.io.*;
import java.util.Arrays;

/**
 * Performs Sholl Analysis on segmented arbors. Several analysis methods are
 * available: Linear (N), Linear (N/S), Semi-log and Log-log as described in
 * Milosevic and Ristanovic, J Theor Biol (2007) 245(1)130-40.
 * The original method is described in Sholl, DA. J Anat (1953) 87(4)387-406.
 *
 * NB: For binary images, background is always considered to be 0, independently
 * of Prefs.blackBackground.
 *
 * @author Tiago Ferreira v2.0, Feb 2012, v3.0 Oct, 2012
 * @author Tom Maddock v1.0, Oct 2005
 */
public class Advanced_Sholl_Analysis implements PlugIn {

    /* Plugin Information */
    private static final String VERSION = "3";
    private static final String URL = "http://imagejdocu.tudor.lu/doku.php?id=plugin:analysis:asa:start";

    /* Bin Function Type Definitions */
    private static final String[] BIN_TYPES = { "Mean", "Median" };
    private static final int BIN_AVERAGE = 0;
    private static final int BIN_MEDIAN  = 1;

    /* Sholl Type Definitions */
    private static final String[] SHOLL_TYPES = { "Intersections", "Norm. Intersections", "Semi-Log", "Log-Log" };
    private static final int SHOLL_N    = 0;
    private static final int SHOLL_NS   = 1;
    private static final int SHOLL_SLOG = 2;
    private static final int SHOLL_LOG  = 3;
    private static final String[] DEGREES = { "4th degree", "5th degree", "6th degree", "7th degree", "8th degree" };

    /* Will image directory be accessible? */
    private static boolean validPath;
    private static String imgPath;

    /* Default parameters and input values */
    private static double startRadius = 10.0;
    private static double endRadius   = 100.0;
    private static double stepRadius  = 1;
    private static double incStep     = 0;
    private static int nSpans         = 1;
    private static int binChoice      = BIN_AVERAGE;
    private static int shollChoice    = SHOLL_N;
    private static int polyChoice     = 1;
    private static boolean fitCurve;
    private static boolean verbose;
    private static boolean mask;
    private static boolean save;

    // If the edge of a group of pixels lies tangent to the sampling circle, multiple
    // intersections with that circle will be counted. With this flag on, we will try to
    // find these "false positives" and throw them out. A way to attempt this (we will be
    // missing some of them) is to throw out 1-pixel groups that exist solely on the edge
    // of a "stair" of target pixels
    private static boolean doSpikeSupression = true;

    /* Common variables */
    private static String unit = "pixels";
    private static double vxWH = 1;
    private static double vxD  = 1;
    private static boolean is3D;
    private static int lowerT;
    private static int upperT;

    /* Boundaries of analysis */
    private static boolean trimBounds;
    private static int minX;
    private static int maxX;
    private static int minY;
    private static int maxY;
    private static int minZ;
    private static int maxZ;

    public void run(final String arg) {

        if (IJ.versionLessThan("1.46h"))
            return;

        // Get current image and its ImageProcessor
        final ImagePlus img = IJ.getImage();
        final ImageProcessor ip = img.getProcessor();

        // Make sure image is the right type. We want to remind the user that
        // the analysis is performed on segmented cells
        if ( !validImage(img, ip) )
            return;

        // Set the 2D/3D Sholl flag
        final int depth = img.getNSlices();
        is3D = depth > 1;

        // Get image calibration. Stacks are likely to have anisotropic voxels
        // with large z-steps. It is unlikely that lateral dimensions will differ
        final Calibration cal = img.getCalibration();
        if (cal.scaled()) {
            vxWH = Math.sqrt(cal.pixelWidth * cal.pixelHeight);
            vxD  = cal.pixelDepth;
            unit = cal.getUnits();
        }
        final double vxSize = (is3D) ? Math.cbrt(vxWH*vxWH*vxD) : vxWH;

        // Initialize center coordinates (in pixel units)
        int x, y;
        final int z = img.getCurrentSlice();

        // Get parameters from current ROI. Prompt for one if none exists
        Roi roi = img.getRoi();
        final boolean validRoi = roi!=null && (roi.getType()==Roi.LINE || roi.getType()==Roi.POINT);

        if (!IJ.macroRunning() && !validRoi) {
            img.deleteRoi();
            Toolbar.getInstance().setTool("line");
            final WaitForUserDialog wd = new WaitForUserDialog(
                              "Please define the largest Sholl radius by creating\n"
                            + "a straight line starting at the center of analysis.\n \n"
                            + "Alternatively, define the focus of the arbor using\n"
                            + "the Point Selection Tool.");
            wd.show();
            if (wd.escPressed())
                return;
            roi = img.getRoi();
        }

        // Initialize angle of the line roi (if any). It will become positive
        // if a line (chord) exists.
        double chordAngle = -1.0;

        // Line: Get center coordinates, length and angle of chord
        if (roi!=null && roi.getType()==Roi.LINE) {

            final Line chord = (Line) roi;
            x = chord.x1;
            y = chord.y1;
            endRadius = vxSize * chord.getRawLength();
            chordAngle = Math.abs(chord.getAngle(x, y, chord.x2, chord.y2));

        // Point: Get center coordinates (x,y)
        } else if (roi != null && roi.getType() == Roi.POINT) {

            final PointRoi point = (PointRoi) roi;
            final Rectangle rect = point.getBounds();
            x = rect.x;
            y = rect.y;

        // Not a proper ROI type
        } else {
            error("Straight Line or Point selection required.");
            return;
        }

        // Show the plugin dialog: Update parameters with user input and
        // retrieve if analysis will be restricted to a hemicircle/hemisphere
        final String trim = showDialog(chordAngle, is3D);
        if (trim==null)
            return;

        // Impose valid parameters
        if (startRadius > endRadius)
            startRadius = 0.0;
        stepRadius = Math.max(vxSize, incStep);

        // Calculate how many samples will be taken
        final int size = (int) ((endRadius-startRadius)/stepRadius)+1;

        // Exit if there are no samples
        if (size==1) {
            error(" Invalid Parameters: Ending radius cannot be larger than\n"
                + "Starting radius and Radius step size must be within range!");
            return;
        }

        img.startTiming();
        IJ.resetEscape();

        // Create arrays for radii (in physical units) and intersection counts
        final double[] radii = new double[size];
        double[] counts = new double[size];

        for (int i = 0; i < size; i++) {
            radii[i] = startRadius + i*stepRadius;
        }

        // Define boundaries of analysis according to orthogonal chords (if any)
        final int maxradius = (int) Math.round(radii[size-1]/vxSize);

        minX = Math.max(x-maxradius, 0);
        maxX = Math.min(maxradius+x, ip.getWidth());
        minY = Math.max(y-maxradius, 0);
        maxY = Math.min(maxradius+y, ip.getHeight());
        minZ = Math.max(z-maxradius, 1);
        maxZ = Math.min(maxradius+z, depth);

        if (trimBounds) {
            if (trim.equalsIgnoreCase("above"))
                maxY = (int) Math.min(y + maxradius, y);
            else if (trim.equalsIgnoreCase("below"))
                minY = (int) Math.max(y - maxradius, y);
            else if (trim.equalsIgnoreCase("right"))
                minX = x;
            else if (trim.equalsIgnoreCase("left"))
                maxX = x;
        }

        // 2D: Analyze the data and return intersection counts with nSpans
        // per radius. 3D: Analysis without nSpans
        if (is3D) {

            // Retrieve raw distances using the interpolate isotropic voxel
            final int[] rawradii = new int[size];
            for (int i = 0; i < size; i++)
                rawradii[i] = (int) Math.round(radii[i] / vxSize);

            counts = analyze3D(x, y, z, rawradii, img);

        } else {

            counts = analyze2D(x, y, radii, vxSize, nSpans, binChoice, ip);

        }

        final String title = img.getTitle();

        // Display the plot and return transformed data
        final double[] grays = plotValues(title, shollChoice, radii, counts, x,
                                          y, z, save, imgPath);

        String exitmsg = "Done. ";

        // Create intersections mask, but check first if it is worth proceeding
        if (grays.length == 0) {

            IJ.beep();
            exitmsg = "Error: All intersection counts were zero! ";

        } else if (mask) {

            final ImagePlus maskimg = makeMask(img, title, grays, maxradius, x, y, cal);

            if (maskimg == null) {

                IJ.beep();
                exitmsg = "Error: Mask could not be created! ";

            } else
                maskimg.show();
        }

        IJ.showProgress(0, 0);
        IJ.showTime(img, img.getStartTime(), exitmsg);

    }

    /**
     * Creates and shows the plugin dialog. Returns the region of the image (relative
     * to the center) to be trimmed from the analysis "None", "Above","Below", "Right"
     * or "Left". Returns null if dialog was canceled
     */
    private static String showDialog(final double chordAngle, final boolean is3D) {

        String trim = "None";

        final GenericDialog gd = new GenericDialog("Advanced Sholl Analysis v"+ VERSION);
        gd.addNumericField("Starting radius:", startRadius, 2, 9, unit);
        gd.addNumericField("Ending radius:", endRadius, 2, 9, unit);
        gd.addNumericField("Radius_step size:", incStep, 2, 9, unit);

        // If 2D, allow multiple samples per radius
        if (!is3D) {
            gd.addNumericField("Samples per radius:", nSpans, 0, 3, "(1-10)");
            gd.setInsets(0, 0, 0);
            gd.addChoice("Samples_integration:", BIN_TYPES, BIN_TYPES[binChoice]);
        }

        gd.setInsets(12, 0, 0);
        gd.addChoice("Sholl method:", SHOLL_TYPES, SHOLL_TYPES[shollChoice]);

        // Prepare choices for hemicircle/hemisphere analysis, an option
        // triggered by the presence of orthogonal lines (chords)
        trimBounds = (chordAngle > -1 && chordAngle % 90 == 0);

        // If an orthogonal chord exists, prompt for quadrants choice
        final String[] quads = new String[2];

        if (trimBounds) {

            if (chordAngle == 90.0) {
                quads[0] = "Right of line";
                quads[1] = "Left of line";
            } else {
                quads[0] = "Above line";
                quads[1] = "Below line";
            }
            final String hemi = is3D ? "hemisphere" : "hemicircle";
            gd.setInsets(12, 6, 3);
            gd.addCheckbox("Restrict analysis to "+ hemi, false);
            gd.addChoice("_", quads, quads[0]);
            gd.setInsets(6, 6, 0);

        } else
            gd.setInsets(12, 6, 0);

        gd.addCheckbox("Fit profile and compute descriptors", fitCurve);
        if (is3D)
            gd.setInsets(3, 33, 3);
        else
            gd.setInsets(3, 56, 3);
        gd.addCheckbox("Show parameters", verbose);
        gd.addChoice("Polynomial:", DEGREES, DEGREES[polyChoice]);

        gd.setInsets(6, 6, 0);
        gd.addCheckbox("Create intersections mask", mask);

        // Offer to save results if local image
        if (validPath) {
            gd.setInsets(5, 6, 0);
            gd.addCheckbox("Save plot values on image folder", save);
        }

        gd.setHelpLabel("Online Help");
        gd.addHelp(URL);
        gd.showDialog();

        // Exit if user pressed cancel
        if (gd.wasCanceled())
            return null;

        // Get values from dialog
        startRadius = Math.max(0, gd.getNextNumber());
        endRadius = Math.max(0, gd.getNextNumber());
        incStep = Math.max(0, gd.getNextNumber());

        if (!is3D) {
            nSpans = (int) Math.max(1, gd.getNextNumber());
            nSpans = Math.min(nSpans, 10);
            binChoice = gd.getNextChoiceIndex();
        }

        shollChoice = gd.getNextChoiceIndex();

        // Update trimBounds flag
        if (trimBounds)
            trimBounds = gd.getNextBoolean();

        // Extract trim choice
        if (trimBounds) {
            final String choice = quads[gd.getNextChoiceIndex()];
            trim = choice.substring(0, choice.indexOf(" "));
        }

        fitCurve = gd.getNextBoolean();
        verbose = gd.getNextBoolean();
        polyChoice = gd.getNextChoiceIndex();
        mask = gd.getNextBoolean();

        if (validPath)
            save = gd.getNextBoolean();

        // Return trim choice
        return trim;
    }

    /** Measures intersections for each sphere surface (pixel coordinates) */
    static public double[] analyze3D(final int xc, final int yc, final int zc,
            final int[] rawradii, final ImagePlus img) {

        int nspheres, xmin, ymin, zmin, xmax, ymax, zmax, count;
        double dx, value;

        // Create an array to hold the results
        final double[] data = new double[nspheres = rawradii.length];

        // Initialize the array holding surface points. It will be as large as
        // the largest sphere surface (last radius in the radii array)
        final int voxels = (int) Math.round(4*Math.PI*(rawradii[nspheres-1]+1)*(rawradii[nspheres-1]+1));
        final int[][] points = new int[voxels][3];

        // Get Image Stack
        final ImageStack stack = img.getStack();

        for (int s = 0; s < nspheres; s++) {

            IJ.showStatus("Sampling sphere "+ (s+1) +"/"+ nspheres +". Press 'Esc' to abort...");
            if (IJ.escapePressed())
                { IJ.beep(); mask = false; return data; }

            xmin = Math.max(xc-rawradii[s], minX);
            ymin = Math.max(yc-rawradii[s], minY);
            zmin = Math.max(zc-rawradii[s], minZ);
            xmax = Math.min(xc+rawradii[s], maxX);
            ymax = Math.min(yc+rawradii[s], maxY);
            zmax = Math.min(zc+rawradii[s], maxZ);
            count = 0;

            for (int z=zmin; z<=zmax; z++) {
                IJ.showProgress(z, zmax+1);
                for (int y=ymin; y<ymax; y++) {
                    for (int x=xmin; x<xmax; x++) {
                        dx = Math.sqrt((x-xc)*(x-xc)+(y-yc)*(y-yc)+(z-zc)*(z-zc));
                        if (Math.abs(dx-rawradii[s])<0.5) {
                            value = stack.getVoxel(x,y,z);
                            if (value >= lowerT && value <= upperT) {
                                points[count][0]   = x;
                                points[count][1]   = y;
                                points[count++][2] = z;
                            }
                        }
                    }
                }
            }

            // We now have the the points intercepting the surface of this Sholl
            // sphere. Lets check if their respective pixels are clustered
            data[s] = count3Dgroups(points, count, 1.5);

            // Since this all this is very computing intensive, exit as soon
            // as a spheres has no interceptions
                //if (count==0) return data;
        }
        return data;
    }

    /**
<<<<<<< HEAD
=======
     * Returns the pixel array for the specified volume range of an 8-bit stack.
     * Does not check if input range is within stack boundaries
     */
     private static int[] getVoxels8(final int x0, final int y0, final int z0, final int x1,
            final int y1, final int z1, final ImageStack stack) {

        final int width = stack.getWidth();
        final int[] voxels = new int[ (x1-x0) * (y1-y0) * (z1-z0+1) ];
        int i = 0;
        for (int z=z0; z<=z1; z++) {
            final byte[] bytes = (byte[])stack.getPixels(z);
            for (int y=y0; y<y1; y++) {
                for (int x=x0; x<x1; x++)
                    voxels[i++] = bytes[y*width+x]&0xff; //tested this recreates the image
            }
        }
        return voxels;
    }

    /**
     * Returns the pixel array for the specified volume range of an 8-bit stack.
     * Does not check if input range is within stack boundaries
     */
    private static int[] getVoxels16(final int x0, final int y0, final int z0, final int x1,
            final int y1, final int z1, final ImageStack stack) {

        final int width = stack.getWidth();
        final int[] voxels = new int[ (x1-x0) * (y1-y0) * (z1-z0+1)];
        int i = 0;
        for (int z=z0; z<=z1; z++) {
            final short[] shorts = (short[])stack.getPixels(z);
            for (int y=y0; y<y1; y++) {
                for (int x=x0; x<x1; x++)
                    voxels[i++] = shorts[y*width+x]&0xffff;
            }
        }
        return voxels;
    }

    /**
>>>>>>> 4fe2ac9... Added copyright notice
     * Analogous to countGroups(), counts clusters of pixels from an array of 3D
     * coordinates, but without SpikeSupression
     */
    static public int count3Dgroups(final int[][] points, final int lastIdx,
            final double threshold) {

        double distance;
        int i, j, k, target, source, dx, dy, dz, groups, len;

        final int[] grouping = new int[len = lastIdx];

        for (i = 0, groups = len; i < groups; i++)
            grouping[i] = i + 1;

        for (i = 0; i < len; i++)
            for (j = 0; j < len; j++) {
                if (i == j)
                    continue;
                dx = points[i][0] - points[j][0];
                dy = points[i][1] - points[j][1];
                dz = points[i][2] - points[j][2];
                distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                if ((distance <= threshold) && (grouping[i] != grouping[j])) {
                    source = grouping[i];
                    target = grouping[j];
                    for (k = 0; k < len; k++)
                        if (grouping[k] == target)
                            grouping[k] = source;
                    groups--;
                }
            }
        return groups;
    }

    /**
     * Does the actual 2D analysis. Accepts an array of radius values and takes
     * the measurements for each
     */
    static public double[] analyze2D(final int xc, final int yc,
            final double[] radii, final double pixelSize, final int binsize,
            final int bintype, final ImageProcessor ip) {

        int i, j, k, rbin, sum, size;
        int[] binsamples, pixels;
        int[][] points;
        double[] data;

        // Create an array to hold the results
        data = new double[size = radii.length];

        // Create an array for the bin samples. Passed binsize value must be
        // at least 1
        binsamples = new int[binsize];

        IJ.showStatus("Sampling "+ size +" radii, "+ binsize
                    + " measurement(s) per radius. Press 'Esc' to abort...");

        // Outer loop to control the analysis bins
        for (i = 0; i < size; i++) {

            // Retrieve the radius in pixel coordinates and set the largest
            // radius of this bin span
            rbin = (int) Math.round(radii[i]/pixelSize + binsize/2);

            // Inner loop to gather samples for each bin
            for (j = 0; j < binsize; j++) {

                // Get the circumference pixels for this radius
                points = getCircumferencePoints(xc, yc, rbin--);
                pixels = getPixels(ip, points);

                // Count the number of intersections
                binsamples[j] = countTargetGroups(pixels, points, ip);

            }

            IJ.showProgress(i, size * binsize);
            if (IJ.escapePressed()) {
                IJ.beep(); return data;
            }

            // Statistically combine bin data
            if (binsize > 1) {
                if (bintype == BIN_MEDIAN) {

                    // Sort the bin data
                    Arrays.sort(binsamples);

                    // Pull out the median value: average the two middle values if no
                    // center exists otherwise pull out the center value
                    if (binsize % 2 == 0)
                        data[i] = ((double) (binsamples[binsize/2] + binsamples[binsize/2 -1])) /2.0;
                    else
                        data[i] = (double) binsamples[binsize/2];

                } else if (bintype == BIN_AVERAGE) {

                    // Mean: Find the samples sum and divide by n. of samples
                    for (sum = 0, k = 0; k < binsize; k++)
                        sum += binsamples[k];
                    data[i] = ((double) sum) / ((double) binsize);

                }

                // There was only one sample
            } else
                data[i] = binsamples[0];

        }

        return data;
    }

    /**
     * Counts how many groups of value v are present in the given data. A group
     * consists of a formation of adjacent pixels, where adjacency is true for
     * all eight neighboring positions around a given pixel.
     */
    static public int countTargetGroups(final int[] pixels, final int[][] rawpoints,
            final ImageProcessor ip) {

        int i, j;
        int[][] points;

        // Count how many target pixels (i.e., foreground, non-zero) we have
        for (i = 0, j = 0; i < pixels.length; i++)
            if (pixels[i] != 0.0)
                j++;

        // Create an array to hold target pixels
        points = new int[j][2];

        // Copy all target pixels into the array
        for (i = 0, j = 0; i < pixels.length; i++)
            if (pixels[i] != 0.0)
                points[j++] = rawpoints[i];

        return countGroups(points, 1.5, ip);

    }

    /**
     * For a set of points in 2D space, counts how many groups of value v there
     * are such that for every point in each group, there exists another point
     * in the same group that is less than threshold units of distance away. If
     * a point is greater than threshold units away from all other points, it is
     * in its own group. For threshold=1.5, this is equivalent to 8-connected
     * clusters
     */
    static public int countGroups(final int[][] points, final double threshold,
            final ImageProcessor ip) {

        double distance;
        int i, j, k, target, source, dx, dy, groups, len;

        // Create an array to hold the point grouping data
        final int[] grouping = new int[len = points.length];

        // Initialize each point to be in a unique group
        for (i = 0, groups = len; i < groups; i++)
            grouping[i] = i + 1;

        for (i = 0; i < len; i++)
            for (j = 0; j < len; j++) {

                // Don't compare the same point with itself
                if (i == j)
                    continue;

                // Compute the distance between the two points
                dx = points[i][0] - points[j][0];
                dy = points[i][1] - points[j][1];
                distance = Math.sqrt(dx * dx + dy * dy);

                // Should these two points be in the same group?
                if ((distance <= threshold) && (grouping[i] != grouping[j])) {

                    // Record which numbers we're changing
                    source = grouping[i];
                    target = grouping[j];

                    // Change all targets to sources
                    for (k = 0; k < len; k++)
                        if (grouping[k] == target)
                            grouping[k] = source;

                    // Update the number of groups
                    groups--;

                }
            }

        if (doSpikeSupression) {
            boolean multigroup;
            int[] px;
            final int[][] testpoints = new int[8][2];

            for (i = 0; i < len; i++) {

                // Check for other members of this group
                for (multigroup = false, j = 0; j < len; j++) {
                    if (i == j)
                        continue;
                    if (grouping[i] == grouping[j]) {
                        multigroup = true;
                        break;
                    }
                }

                // If not a single-pixel group, try again
                if (multigroup)
                    continue;

                // Save the coordinates of this point
                dx = points[i][0];
                dy = points[i][1];

                // Calculate the 8 neighbors surrounding this point
                testpoints[0][0] = dx-1;   testpoints[0][1] = dy+1;
                testpoints[1][0] = dx  ;   testpoints[1][1] = dy+1;
                testpoints[2][0] = dx+1;   testpoints[2][1] = dy+1;
                testpoints[3][0] = dx-1;   testpoints[3][1] = dy  ;
                testpoints[4][0] = dx+1;   testpoints[4][1] = dy  ;
                testpoints[5][0] = dx-1;   testpoints[5][1] = dy-1;
                testpoints[6][0] = dx  ;   testpoints[6][1] = dy-1;
                testpoints[7][0] = dx+1;   testpoints[7][1] = dy-1;

                // Pull out the pixel values for these points
                px = getPixels(ip, testpoints);

                // Now perform the stair checks
                if ((px[0]!=0 && px[1]!=0 && px[3]!=0 && px[4]==0 && px[6]==0 && px[7]==0) ||
                    (px[1]!=0 && px[2]!=0 && px[4]!=0 && px[3]==0 && px[5]==0 && px[6]==0) ||
                    (px[4]!=0 && px[6]!=0 && px[7]!=0 && px[0]==0 && px[1]==0 && px[3]==0) ||
                    (px[3]!=0 && px[5]!=0 && px[6]!=0 && px[1]==0 && px[2]==0 && px[4]==0))

                    groups--;

            }
        }

        return groups;
    }

    /**
     * For a given set of points, returns values of 1 for pixel intensities
     * within the thresholded range, otherwise returns 0 values
     */
    static public int[] getPixels(final ImageProcessor ip, final int[][] points) {

        int value;

        // Initialize the array to hold the pixel values. Arrays of integral
        // types have a default value of 0
        final int[] pixels = new int[points.length];

        // Put the pixel value for each circumference point in the pixel array
        for (int i = 0; i < pixels.length; i++) {

            // We already filtered out of bounds coordinates in
            // getCircumferencePoints
            value = ip.getPixel(points[i][0], points[i][1]);
            if (value >= lowerT && value <= upperT)
                pixels[i] = 1;
        }

        return pixels;
    }

    /**
     * Returns the location of pixels clockwise along a (1-pixel wide) circumference
     * using Bresenham's Circle Algorithm
     */
    static public int[][] getCircumferencePoints(final int cx, final int cy, int r) {

        // Initialize algorithm variables
        int i = 0, x = 0, y = r, err = 0, errR, errD;

        // Array to store first 1/8 of points relative to center
        final int[][] data = new int[++r][2];

        do {
            // Add this point as part of the circumference
            data[i][0] = x;
            data[i++][1] = y;

            // Calculate the errors for going right and down
            errR = err + 2 * x + 1;
            errD = err - 2 * y + 1;

            // Choose which direction to go
            if (Math.abs(errD) < Math.abs(errR)) {
                y--;
                err = errD; // Go down
            } else {
                x++;
                err = errR; // Go right
            }
        } while (x <= y);

        // Create an array to hold the absolute coordinates
        final int[][] points = new int[r * 8][2];

        // Loop through the relative circumference points
        for (i = 0; i < r; i++) {

            // Pull out the point for quick access;
            x = data[i][0];
            y = data[i][1];

            // Convert the relative point to an absolute point
            points[i][0] = x + cx;
            points[i][1] = y + cy;

            // Use geometry to calculate remaining 7/8 of the circumference points
            points[r*4-i-1][0] =  x + cx;   points[r*4-i-1][1] = -y + cy;
            points[r*8-i-1][0] = -x + cx;   points[r*8-i-1][1] =  y + cy;
            points[r*4+i]  [0] = -x + cx;   points[r*4+i]  [1] = -y + cy;
            points[r*2-i-1][0] =  y + cx;   points[r*2-i-1][1] =  x + cy;
            points[r*2+i]  [0] =  y + cx;   points[r*2+i]  [1] = -x + cy;
            points[r*6+i]  [0] = -y + cx;   points[r*6+i]  [1] =  x + cy;
            points[r*6-i-1][0] = -y + cx;   points[r*6-i-1][1] = -x + cy;

        }

        // Count how many points are out of bounds, while eliminating
        // duplicates. Duplicates are always at multiples of r (8 points)
        int pxX, pxY, count = 0, j = 0;
        for (i = 0; i < points.length; i++) {

            // Pull the coordinates out of the array
            pxX = points[i][0];
            pxY = points[i][1];

            if ((i+1)%r!=0 && pxX>=minX && pxX<=maxX && pxY>=minY && pxY<=maxY)
                count++;
        }

        // Create the final array containing only unique points within bounds
        final int[][] refined = new int[count][2];

        for (i = 0; i < points.length; i++) {

            pxX = points[i][0];
            pxY = points[i][1];

            if ((i+1)%r!=0 && pxX>=minX && pxX<=maxX && pxY>=minY && pxY<=maxY) {
                refined[j][0] = pxX;
                refined[j++][1] = pxY;

            }

        }

        // Return the array
        return refined;
    }

    /** Creates Results table and Sholl plot, performing curve fitting */
    static public double[] plotValues(final String title, final int mthd, final double[] xpoints,
            final double[] ypoints, final int xc, final int yc, final int zc, final boolean saveplot,
            final String savepath) {

        final int size = ypoints.length;
        int i, j, nsize = 0;
        final StringBuffer plotLabel = new StringBuffer();

        IJ.showStatus("Preparing Results...");

        // Zero intersections are problematic for logs and polynomials. Long
        // stretches of zeros often cause sharp "bumps" on the fitted curve.
        // Setting zeros to NaN is not option as it would impact the CurveFitter
        for (i = 0; i < size; i++)
            if (ypoints[i] != 0)
                nsize++;

        // Do not proceed if there are no counts or mismatch between values
        if (nsize == 0 || size > xpoints.length)
            return new double[0];

        final double[] x = new double[nsize];
        double[] y = new double[nsize];
        final double[] logY = new double[nsize];
        double sumY = 0.0;

        for (i = 0, j = 0; i < size; i++) {

            if (ypoints[i] != 0.0) {
                x[j] = xpoints[i];
                y[j] = ypoints[i];
                // Normalize log values to area of circle/volume of sphere
                if (is3D)
                    logY[j] = Math.log(y[j] / (Math.PI * x[j]*x[j]*x[j] * 4/3));
                else
                    logY[j] = Math.log(y[j] / (Math.PI * x[j]*x[j]));
                sumY += y[j++];
            }

        }

        // Place parameters on a dedicated table
        ResultsTable rt;
        final String shollTable = "Sholl Results";
        final Frame window = WindowManager.getFrame(shollTable);
        if (window == null)
            rt = new ResultsTable();
        else
            rt = ((TextWindow) window).getTextPanel().getResultsTable();

        rt.incrementCounter();
        rt.setPrecision(Analyzer.precision);
        rt.addLabel("Image", title + " (" + unit + ")");
        rt.addValue("Lower Thold", lowerT);
        rt.addValue("Upper Thold", upperT);
        rt.addValue("Method #", mthd + 1);
        rt.addValue("X center (px)", xc);
        rt.addValue("Y center (px)", yc);
        rt.addValue("Z center (slice)", zc);
        rt.addValue("Starting radius", startRadius);
        rt.addValue("Ending radius", endRadius);
        rt.addValue("Radius step", stepRadius);
        rt.addValue("Samples per radius", is3D ? 1 : nSpans);
        rt.addValue("Sampled radii", size);
        rt.addValue("Sum Inters.", sumY);
        rt.addValue("Avg Inters.", sumY/size);
        rt.addValue("Zero Inters.", size-nsize);
        rt.show(shollTable); // addResults();

        // Calculate Sholl decay: the slope of fitted regression on Semi-log Sholl
        CurveFitter cf = new CurveFitter(x, logY);
        cf.doFit(CurveFitter.STRAIGHT_LINE, false);

        double[] parameters = cf.getParams();
        plotLabel.append("k= " + IJ.d2s(parameters[0], -3));
        rt.addValue("Sholl decay", parameters[0]);
        rt.addValue("R^2 (decay)", cf.getRSquared());

        // Define a global analysis title
        final String longtitle = "Sholl ["+ SHOLL_TYPES[mthd] +"] :: "+ title;

        // Abort curve fitting when dealing with small datasets that are prone to
        // inflated coefficients of determination
        if (fitCurve && nsize <= 6) {
            fitCurve = false;
            IJ.log(longtitle +":\nCurve fitting not performed: Not enough data points");
        }

        // Define plot axes
        String xTitle, yTitle;
        final boolean xAxislog  = mthd == SHOLL_LOG;
        final boolean yAxislog  = mthd == SHOLL_LOG || mthd == SHOLL_SLOG;
        final boolean yAxisnorm = mthd == SHOLL_NS;

        if (xAxislog) {

            xTitle = is3D ? "log(3D distance)" : "log(2D distance)";
            for (i = 0; i < nsize; i++)
                x[i] = Math.log(x[i]);

        } else {
            xTitle = is3D ? "3D distance ("+ unit +")" : "2D distance ("+ unit +")";
        }

        if (yAxislog) {

            yTitle = is3D ? "log(N. Inters./Sphere volume)" : "log(N. Inters./Circle area)";
            y = (double[])logY.clone();

        } else if (yAxisnorm) {

                yTitle = is3D ? "N. Inters./Sphere volume (" + unit + "\u00B3)" :
                                "N. Inters./Circle area (" + unit + "\u00B2)";
                for (i=0; i<nsize; i++)
                    y[i] = Math.exp(logY[i]);

        } else {
            yTitle = "N. of Intersections";
        }

        // Create an empty plot: The plot constructor only allows the usage of the 'flags'
        // argument with initial arrays
        final double[] empty = null;
        final int flags = Plot.X_FORCE2GRID + Plot.X_TICKS + Plot.X_NUMBERS
                        + Plot.Y_FORCE2GRID + Plot.Y_TICKS + Plot.Y_NUMBERS;
        final Plot plot = new Plot("Plot "+ longtitle, xTitle, yTitle, empty, empty, flags);

        // Set plot limits
        final double[] xScale = Tools.getMinMax(x);
        final double[] yScale = Tools.getMinMax(y);
        plot.setLimits(xScale[0], xScale[1], yScale[0], yScale[1]);

        // Add original data (default color is black)
        plot.setColor(Color.GRAY);
        plot.addPoints(x, y, Plot.CROSS);

        // Exit and return raw data if no fitting is done
        if (!fitCurve) {
            plot.show();
            return y;
        }

        // Perform a new fit if data is not Semi-log Sholl
        if (mthd!=SHOLL_SLOG )
            cf = new CurveFitter(x, y);

        // cf.setRestarts(2); // default: 2;
        // cf.setMaxIterations(25000); //default: 25000

        if (mthd == SHOLL_N) {
            if (DEGREES[polyChoice].startsWith("4")) {
                cf.doFit(CurveFitter.POLY4, false);
            } else if (DEGREES[polyChoice].startsWith("5")) {
                cf.doFit(CurveFitter.POLY5, false);
            } else if (DEGREES[polyChoice].startsWith("6")) {
                cf.doFit(CurveFitter.POLY6, false);
            } else if (DEGREES[polyChoice].startsWith("7")) {
                cf.doFit(CurveFitter.POLY7, false);
            } else if (DEGREES[polyChoice].startsWith("8")) {
                cf.doFit(CurveFitter.POLY8, false);
            }
        } else if (mthd == SHOLL_NS) {
            cf.doFit(CurveFitter.POWER, false);
        } else if (mthd == SHOLL_LOG) {
            cf.doFit(CurveFitter.EXP_WITH_OFFSET, false);
        }

        // Get parameters of fitted function
        if (mthd != SHOLL_SLOG)
            parameters = cf.getParams();

        // Get fitted data
        final double[] fy = new double[nsize];
        for (i = 0; i < nsize; i++)
            fy[i] = cf.f(parameters, x[i]);

        // Initialize morphometric descriptors
        double cv = 0, cr = 0, mv = 0, ri = 0;

        // Linear Sholl: Calculate Critical value (cv), Critical radius (cr),
        // Mean Sholl value (mv) and Ramification (Schoenen) index (ri)
        if (mthd == SHOLL_N) {

            // Get coordinates of cv, the local maximum of polynomial. We'll
            // iterate around the index of highest fitted value to retrive values
            // empirically. This is probably the most ineficient way of doing it
            final int maxIdx = CurveFitter.getMax(fy);
            final int iterations = 1000;
            final double crLeft  = (x[Math.max(maxIdx-1, 0)] + x[maxIdx]) / 2;
            final double crRight = (x[Math.min(maxIdx+1, nsize-1)] + x[maxIdx]) / 2;
            final double step = (crRight-crLeft) / iterations;
            double crTmp, cvTmp;
            for (i = 0; i < iterations; i++) {
                crTmp = crLeft + (i*step);
                cvTmp = cf.f(parameters, crTmp);
                if (cvTmp > cv)
                    { cv = cvTmp; cr = crTmp; }
            }

            // Calculate mv, the mean value of the fitted Sholl function.
            // This can be done assuming that the mean value is the height
            // of a rectangle that has the width of (NonZeroEndRadius -
            // NonZeroStartRadius) and the same area of the area under the
            // fitted curve on that discrete interval
            for (i = 0; i < parameters.length-1; i++) //-1?
                mv += (parameters[i]/(i+1)) * Math.pow(xScale[1]-xScale[0], i);

            // Highlight the mean Sholl value on the plot
            plot.setLineWidth(1);
            plot.setColor(Color.lightGray);
            plot.drawLine(xScale[0], mv, xScale[1], mv);

            // Calculate the ramification index: cv/N. of primary branches
            ri = cv / y[0];

            // Append calculated parameters to plot label
            plotLabel.append("\nCv= "+ IJ.d2s(cv, 2));
            plotLabel.append("\nCr= "+ IJ.d2s(cr, 2));
            plotLabel.append("\nMv= "+ IJ.d2s(mv, 2));
            plotLabel.append("\nRI= "+ IJ.d2s(ri, 2));
            plotLabel.append("\n" + DEGREES[polyChoice]);

        } else {
            cv = cr = mv = ri = Double.NaN;
        }

        rt.addValue("Critical value", cv);
        rt.addValue("Critical radius", cr);
        rt.addValue("Mean value", mv);
        rt.addValue("Ramification index", ri);
        rt.addValue("Polyn. degree", mthd==SHOLL_N ? parameters.length-2 : Double.NaN);

        // Register quality of fit
        plotLabel.append("\nR\u00B2= "+ IJ.d2s(cf.getRSquared(), 3));
        rt.addValue("R^2 (curve)", cf.getRSquared());

        // Add label to plot
        plot.changeFont(new Font("SansSerif", Font.PLAIN, 11));
        plot.setColor(Color.BLACK);
        plot.addLabel(0.8, 0.085, plotLabel.toString());

        // Plot fitted curve
        plot.setColor(Color.BLUE);
        plot.setLineWidth(2);
        plot.addPoints(x, fy, PlotWindow.LINE);

        if (verbose) {
            IJ.log("\n*** "+ longtitle +", fitting details:"+ cf.getResultString());
        }

        // Show the plot window, save plot values (if requested), update
        // results and return fitted data
        final PlotWindow pw = plot.show();

        if (saveplot) {

            final ResultsTable rtp = pw.getResultsTable();
            try {
                rtp.saveAs(savepath + File.separator
                        + title.replaceFirst("[.][^.]+$", "") + "_Sholl-M"
                        + (mthd + 1) + Prefs.get("options.ext", ".csv"));
            } catch (final IOException e) {
                IJ.log(">>>> "+ longtitle +":\n"+ e);
            }
        }

        rt.show(shollTable);
        return fy;
    }

    /**
     * Creates a 2D Sholl heatmap by applying measured values to the foregroud pixels
     * of a copy of the analyzed image
     */
    private ImagePlus makeMask(final ImagePlus img, final String ttl, final double[] values,
            final int lastRadius, final int xc, final int yc, final Calibration cal) {

        // Check if analyzed image remains available
        if ( img.getWindow()==null ) return null;

        IJ.showStatus("Preparing intersections mask...");

        ImageProcessor ip;

        // Work on a stack projection when dealing with a volume
        if (is3D) {
            final ZProjector zp = new ZProjector(img);
            zp.setMethod(ZProjector.MAX_METHOD);
            zp.setStartSlice(minZ);
            zp.setStopSlice(maxZ);
            zp.doProjection();
            ip = zp.getProjection().getProcessor();
        } else {
            ip = img.getProcessor();
        }

        // Heatmap will be a 32-bit image so that it can hold any real number
        final ImageProcessor mp = new FloatProcessor(ip.getWidth(), ip.getHeight());

        final int drawSteps = values.length;
        final int firstRadius = (int) Math.round(startRadius/vxWH);
        final int drawWidth = (int) Math.round((lastRadius-startRadius)/drawSteps);

        for (int i = 0; i < drawSteps; i++) {

            IJ.showProgress(i, drawSteps);
            int drawRadius = firstRadius + (i*drawWidth);

            for (int j = 0; j < drawWidth; j++) {

                // this will already exclude pixels out of bounds
                final int[][] points = getCircumferencePoints(xc, yc, drawRadius++);
                for (int k = 0; k < points.length; k++)
                    for (int l = 0; l < points[k].length; l++) {
                        final double value = ip.getPixel(points[k][0], points[k][1]);
                        if (value >= lowerT && value <= upperT)
                            mp.putPixelValue(points[k][0], points[k][1], values[i]);
                    }
            }
        }
        mp.resetMinAndMax();

        final ImagePlus img2 = new ImagePlus("Sholl mask ["+ SHOLL_TYPES[shollChoice] +"] :: "+ ttl, mp);

        // Apply calibration, set mask label and mark center of analysis
        img2.setCalibration(cal);
        img2.setProperty("Label", fitCurve ? "Fitted data" : "Raw data");
        img2.setRoi(new PointRoi(xc, yc));
        IJ.run(img2, "Fire", ""); // "Fire", "Ice", "Spectrum", "Redgreen"
        return img2;
    }


    /** Checks if image is valid (segmented grayscale) and sets validPath */
    boolean validImage(final ImagePlus img, final ImageProcessor ip) {

        String exitmsg = "";

        final int type = ip.getBitDepth();

        if (type==24)
            exitmsg = "RGB color images are not supported.";
        else if (type==32)
            exitmsg = "32-bit grayscale images are not supported.";
        else if (ip.isBinary()) {
            lowerT = upperT = 255;
            if (ip.isInvertedLut()) {
                ip.setThreshold(lowerT, upperT, ImageProcessor.RED_LUT);
                img.updateAndDraw();
            }
        } else {  // 8/16-bit grayscale image

            final double lower = ip.getMinThreshold();
            if (lower==ImageProcessor.NO_THRESHOLD)
                exitmsg = "Image is not thresholded.";
            else {
                lowerT = (int) lower;
                upperT = (int) ip.getMaxThreshold();
            }
        }

        if (!"".equals(exitmsg)) {
            error(exitmsg + "\n \nThis plugin requires a segmented arbor. Either:\n"
                  + "    - A binary image (Arbor: non-zero value)\n"
                  + "    - A thresholded grayscale image (8/16-bit)");
            return false;
        }

        // Retrieve image path and check if it is valid
        imgPath = IJ.getDirectory("image");
        if (imgPath == null) {
            validPath = false;
        } else {
            final File dir = new File(imgPath);
            validPath = dir.exists() && dir.isDirectory();
        }

        return true;
    }

    /** Creates improved error messages */
    void error(final String msg) {
        if (IJ.macroRunning())
            IJ.error("Advanced Sholl Analysis Error", msg);
        else {
            final GenericDialog gd = new GenericDialog("Advanced Sholl Analysis Error");
            gd.setInsets(0,0,0);
            gd.addMessage(msg);
            gd.addHelp(URL);
            gd.setHelpLabel("Online Help");
            gd.hideCancelButton();
            gd.showDialog();
        }
    }
}
