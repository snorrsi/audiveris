//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                           S t a f f                                            //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2016. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.GlyphIndex;

import omr.math.GeoUtil;
import omr.math.Population;

import omr.run.Orientation;

import omr.sheet.grid.LineInfo;
import omr.sheet.grid.StaffFilament;
import omr.sheet.grid.StaffPeak;
import omr.sheet.header.StaffHeader;
import omr.sheet.note.NotePosition;

import omr.sig.inter.AbstractInter;
import omr.sig.inter.AbstractNoteInter;
import omr.sig.inter.BarlineInter;
import omr.sig.inter.Inter;
import omr.sig.inter.InterEnsemble;
import omr.sig.inter.LedgerInter;

import omr.ui.util.AttachmentHolder;
import omr.ui.util.BasicAttachmentHolder;

import omr.util.HorizontalSide;
import static omr.util.HorizontalSide.*;
import omr.util.Jaxb;
import omr.util.Navigable;
import omr.util.VerticalSide;
import static omr.util.VerticalSide.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Class {@code Staff} handles physical information of a staff with its lines.
 * <p>
 * Note: All methods are meant to provide correct results, regardless of the actual number of lines
 * in the staff instance.
 *
 * @author Hervé Bitteur
 */
@XmlAccessorType(XmlAccessType.NONE)
public class Staff
        implements AttachmentHolder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(
            Staff.class);

    /** To sort by staff id. */
    public static final Comparator<Staff> byId = new Comparator<Staff>()
    {
        @Override
        public int compare (Staff o1,
                            Staff o2)
        {
            return Integer.compare(o1.getId(), o2.getId());
        }
    };

    /** To sort by staff abscissa. */
    public static final Comparator<Staff> byAbscissa = new Comparator<Staff>()
    {
        @Override
        public int compare (Staff o1,
                            Staff o2)
        {
            return Integer.compare(o1.left, o2.left);
        }
    };

    //~ Instance fields ----------------------------------------------------------------------------
    //
    // Persistent data
    //----------------
    //
    /** Staff id. (counted globally from 1 within the sheet) */
    @XmlAttribute
    @XmlJavaTypeAdapter(type = int.class, value = Jaxb.StringIntegerAdapter.class)
    private final int id;

    /** Left extrema. (abscissa at beginning of lines) */
    @XmlAttribute
    private int left;

    /** Right extrema. (abscissa at end of lines) */
    @XmlAttribute
    private int right;

    /** Flag for short staff. (With a neighbor staff on left or right side) */
    @XmlAttribute
    private Boolean isShort;

    /** Sequence of staff lines. (from top to bottom) */
    @XmlElement(name = "line")
    private final List<LineInfo> lines;

    /** Staff Header information. */
    @XmlElement
    private StaffHeader header;

    /** Sequence of bar lines. */
    @XmlElement(name = "bar")
    private List<BarlineInter> bars;

    /** Map of ledgers nearby. */
    @XmlElement(name = "ledgers")
    @XmlJavaTypeAdapter(Staff.LedgerAdapter.class)
    private final TreeMap<Integer, List<LedgerInter>> ledgerMap = new TreeMap<Integer, List<LedgerInter>>();

    /** Notes (heads & rests) assigned to this staff. */
    @XmlElementRef
    private LinkedHashSet<AbstractNoteInter> notes = new LinkedHashSet<AbstractNoteInter>();

    /** Other staff-related inters. Containment is needed for their staff info to persist. */
    @XmlElementRef
    private final List<AbstractInter> others = new ArrayList<AbstractInter>();

    // Transient data
    //---------------
    //
    /** To flag a dummy staff. */
    private boolean dummy;

    /** Side bars, if any. */
    private final Map<HorizontalSide, BarlineInter> sideBars = new EnumMap<HorizontalSide, BarlineInter>(
            HorizontalSide.class);

    /**
     * Area around the staff.
     * The same area strategy applies for staves and for systems:
     * The purpose is to contain relevant entities (sections, glyphs) for the staff at hand but a
     * given entity may be contained by several staff areas when it is located in the inter-staff
     * gutter.
     * There is no need to be very precise, but a staff line cannot belong to several staff areas.
     * Horizontally, the area is extended half way to the next staff if any, otherwise to the limit
     * of the page.
     * Vertically, the area is extended to the first encountered line (exclusive) of the next staff
     * if any, otherwise to the limit of the page.
     */
    private Area area;

    /**
     * Scale specific to this staff. [not used for the time being]
     * (since different staves in a page may exhibit different scales)
     */
    private final Scale specificScale;

    /** Sequence of brace / bracket / bar lines peaks kept. */
    private List<StaffPeak> peaks;

    /** Containing system. */
    @Navigable(false)
    private SystemInfo system;

    /** Potential attachments. */
    private final AttachmentHolder attachments = new BasicAttachmentHolder();

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create info about a staff, with its contained staff lines.
     *
     * @param id            the id of the staff
     * @param left          abscissa of the left side
     * @param right         abscissa of the right side
     * @param specificScale specific scale detected for this staff
     * @param lines         the sequence of contained staff lines
     */
    public Staff (int id,
                  double left,
                  double right,
                  Scale specificScale,
                  List<LineInfo> lines)
    {
        this.id = id;
        this.left = (int) Math.rint(left);
        this.right = (int) Math.rint(right);
        this.specificScale = specificScale;
        this.lines = lines;
    }

    /**
     * No-arg constructor needed for JAXB.
     */
    public Staff ()
    {
        this.id = 0;
        this.lines = null;
        this.specificScale = null;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //
    //---------------//
    // addAttachment //
    //---------------//
    @Override
    public void addAttachment (String id,
                               Shape attachment)
    {
        attachments.addAttachment(id, attachment);
    }

    //-----------//
    // addLedger //
    //-----------//
    /**
     * Add a ledger glyph to the collection.
     *
     * @param ledger the ledger to add
     * @param index  the staff-based index for ledger line
     */
    public void addLedger (LedgerInter ledger,
                           int index)
    {
        assert ledger != null : "Cannot add a null ledger";

        List<LedgerInter> ledgerSet = ledgerMap.get(index);

        if (ledgerSet == null) {
            ledgerSet = new ArrayList<LedgerInter>();
            ledgerMap.put(index, ledgerSet);
        }

        ledgerSet.add(ledger);
    }

    //-----------//
    // addLedger //
    //-----------//
    /**
     * Add a ledger glyph to the collection, computing line index from
     * glyph pitch position.
     *
     * @param ledger the ledger glyph to add
     */
    public void addLedger (LedgerInter ledger)
    {
        assert ledger != null : "Cannot add a null ledger";

        addLedger(ledger, getLedgerLineIndex(pitchPositionOf(ledger.getGlyph().getCentroid())));
    }

    //---------//
    // addNote //
    //---------//
    /**
     * Assign a note (head or rest) to this staff.
     *
     * @param note the note to add to staff collection
     */
    public void addNote (AbstractNoteInter note)
    {
        notes.add(note);
    }

    //---------------//
    // addOtherInter //
    //---------------//
    public void addOtherInter (AbstractInter inter)
    {
        Objects.requireNonNull(inter, "Cannot add other null inter");
        others.add(inter);
    }

    //-------------//
    // afterReload //
    //-------------//
    public void afterReload ()
    {
        try {
            for (AbstractNoteInter note : notes) {
                note.setStaff(this);
            }

            for (AbstractInter other : others) {
                other.setStaff(this);
            }
        } catch (Exception ex) {
            logger.warn("Error in " + getClass() + " afterReload() " + ex, ex);
        }
    }

    //------------//
    // distanceTo //
    //------------//
    /**
     * Report the vertical (algebraic) distance between staff and the provided point.
     * Distance is negative if the point is within the staff height and positive if outside.
     *
     * @param point the provided point
     * @return algebraic distance between staff and point, specified in pixels
     */
    public int distanceTo (Point2D point)
    {
        final double top = getFirstLine().yAt(point.getX());
        final double bottom = getLastLine().yAt(point.getX());

        return (int) Math.max(top - point.getY(), point.getY() - bottom);
    }

    //------//
    // dump //
    //------//
    /**
     * A utility meant for debugging.
     */
    public void dump ()
    {
        System.out.println("StaffInfo" + getId() + " left=" + left + " right=" + right);

        int i = 0;

        for (LineInfo line : lines) {
            System.out.println(" LineInfo" + i++ + " " + line.toString());
        }
    }

    //-------//
    // gapTo //
    //-------//
    /**
     * Report the vertical gap between staff and the provided rectangle.
     *
     * @param rect the provided rectangle
     * @return 0 if the rectangle intersects the staff, otherwise the vertical
     *         distance from staff to closest edge of the rectangle
     */
    public int gapTo (Rectangle rect)
    {
        Point center = GeoUtil.centerOf(rect);
        int staffTop = getFirstLine().yAt(center.x);
        int staffBot = getLastLine().yAt(center.x);
        int glyphTop = rect.y;
        int glyphBot = (glyphTop + rect.height) - 1;

        // Check overlap
        int top = Math.max(glyphTop, staffTop);
        int bot = Math.min(glyphBot, staffBot);

        if (top <= bot) {
            return 0;
        }

        // No overlap, compute distance
        int dist = Integer.MAX_VALUE;
        dist = Math.min(dist, Math.abs(staffTop - glyphTop));
        dist = Math.min(dist, Math.abs(staffTop - glyphBot));
        dist = Math.min(dist, Math.abs(staffBot - glyphTop));
        dist = Math.min(dist, Math.abs(staffBot - glyphBot));

        return dist;
    }

    //-------------//
    // getAbscissa //
    //-------------//
    /**
     * Report the staff abscissa, on the provided side.
     *
     * @param side provided side
     * @return the staff abscissa
     */
    public int getAbscissa (HorizontalSide side)
    {
        if (side == HorizontalSide.LEFT) {
            return left;
        } else {
            return right;
        }
    }

    //---------//
    // getArea //
    //---------//
    /**
     * Report the area defined by the staff limits.
     *
     * @return the whole staff area
     */
    public Area getArea ()
    {
        if (area == null) {
            system.getSheet().getStaffManager().computeStaffArea(this);
        }

        return area;
    }

    //---------------//
    // getAreaBounds //
    //---------------//
    /**
     * Report the bounding box of the staff area.
     *
     * @return the lazily computed bounding box
     */
    public Rectangle2D getAreaBounds ()
    {
        return getArea().getBounds2D();
    }

    //----------------//
    // getAttachments //
    //----------------//
    @Override
    public Map<String, Shape> getAttachments ()
    {
        return attachments.getAttachments();
    }

    //---------//
    // getBars //
    //---------//
    /**
     * @return the bars
     */
    public List<BarlineInter> getBars ()
    {
        return Collections.unmodifiableList(bars);
    }

    /**
     * @return the clefStop
     */
    public Integer getClefStop ()
    {
        if (header.clefRange.valid) {
            return header.clefRange.stop;
        }

        return null;
    }

    //------------------//
    // getClosestLedger //
    //------------------//
    /**
     * Report the closest ledger (if any) to provided point, located between the point
     * and this staff.
     *
     * @param point the provided point
     * @return the closest ledger found, or null
     */
    public IndexedLedger getClosestLedger (Point2D point)
    {
        IndexedLedger bestLedger = null;
        double top = getFirstLine().yAt(point.getX());
        double bottom = getLastLine().yAt(point.getX());
        double rawPitch = (4.0d * ((2 * point.getY()) - bottom - top)) / (bottom - top);

        if (Math.abs(rawPitch) <= 5) {
            return null;
        }

        int interline = specificScale.getInterline();
        Rectangle2D searchBox;

        if (rawPitch < 0) {
            searchBox = new Rectangle2D.Double(
                    point.getX(),
                    point.getY(),
                    0,
                    top - point.getY() + 1);
        } else {
            searchBox = new Rectangle2D.Double(point.getX(), bottom, 0, point.getY() - bottom + 1);
        }

        //searchBox.grow(interline, interline);
        searchBox.setRect(
                searchBox.getX() - interline,
                searchBox.getY() - interline,
                searchBox.getWidth() + (2 * interline),
                searchBox.getHeight() + (2 * interline));

        // Browse all staff ledgers
        Set<IndexedLedger> foundLedgers = new HashSet<IndexedLedger>();

        for (Map.Entry<Integer, List<LedgerInter>> entry : ledgerMap.entrySet()) {
            for (LedgerInter ledger : entry.getValue()) {
                if (ledger.getBounds().intersects(searchBox)) {
                    foundLedgers.add(new IndexedLedger(ledger, entry.getKey()));
                }
            }
        }

        if (!foundLedgers.isEmpty()) {
            // Use the closest ledger
            double bestDist = Double.MAX_VALUE;

            for (IndexedLedger iLedger : foundLedgers) {
                Point2D center = iLedger.ledger.getGlyph().getCenter();
                double dist = Math.abs(center.getY() - point.getY());

                if (dist < bestDist) {
                    bestDist = dist;
                    bestLedger = iLedger;
                }
            }
        }

        return bestLedger;
    }

    //----------------//
    // getClosestLine //
    //----------------//
    /**
     * Report the staff line which is closest to the provided point.
     *
     * @param point the provided point
     * @return the closest line found
     */
    public LineInfo getClosestLine (Point2D point)
    {
        double pos = pitchPositionOf(point);
        int idx = (int) Math.rint((pos + (lines.size() - 1)) / 2);

        if (idx < 0) {
            idx = 0;
        } else if (idx > (lines.size() - 1)) {
            idx = lines.size() - 1;
        }

        return lines.get(idx);
    }

    //----------------------//
    // getDefiningPointSize //
    //----------------------//
    public static Scale.Fraction getDefiningPointSize ()
    {
        return constants.definingPointSize;
    }

    //----------------//
    // getEndingSlope //
    //----------------//
    /**
     * Report mean ending slope, on the provided side.
     * We discard highest and lowest absolute slopes, and return the average
     * values for the remaining ones.
     *
     * @param side which side to select (left or right)
     * @return a "mean" value
     */
    public double getEndingSlope (HorizontalSide side)
    {
        List<Double> slopes = new ArrayList<Double>(lines.size());

        for (LineInfo l : lines) {
            StaffFilament line = (StaffFilament) l;
            slopes.add(line.getSlopeAt(line.getEndPoint(side).getX(), Orientation.HORIZONTAL));
        }

        Collections.sort(slopes);

        double sum = 0;

        for (Double slope : slopes.subList(1, slopes.size() - 1)) {
            sum += slope;
        }

        return sum / (slopes.size() - 2);
    }

    //--------------------//
    // getLedgerLineIndex //
    //--------------------//
    /**
     * Compute staff-based line index, based on provided pitch position
     *
     * @param pitchPosition the provided pitch position
     * @return the computed line index
     */
    public static int getLedgerLineIndex (double pitchPosition)
    {
        if (pitchPosition > 0) {
            return (int) Math.rint(pitchPosition / 2) - 2;
        } else {
            return (int) Math.rint(pitchPosition / 2) + 2;
        }
    }

    //------------------------//
    // getLedgerPitchPosition //
    //------------------------//
    /**
     * Report the pitch position of a ledger WRT the related staff.
     * <p>
     * TODO: This implementation assumes a 5-line staff.
     * But can we have ledgers on a staff with more (of less) than 5 lines?
     *
     * @param lineIndex the ledger line index
     * @return the ledger pitch position
     */
    public static int getLedgerPitchPosition (int lineIndex)
    {
        //        // Safer, for the time being...
        //        if (getStaff()
        //                .getLines()
        //                .size() != 5) {
        //            throw new RuntimeException("Only 5-line staves are supported");
        //        }
        if (lineIndex > 0) {
            return 4 + (2 * lineIndex);
        } else {
            return -4 + (2 * lineIndex);
        }
    }

    //--------------------//
    // showDefiningPoints //
    //--------------------//
    public static Boolean showDefiningPoints ()
    {
        return constants.showDefiningPoints.isSet();
    }

    //--------------//
    // getFirstLine //
    //--------------//
    /**
     * Report the first line in the series.
     *
     * @return the first line
     */
    public LineInfo getFirstLine ()
    {
        return lines.get(0);
    }

    //-----------//
    // getHeader //
    //-----------//
    /**
     * @return the StaffHeader information
     */
    public StaffHeader getHeader ()
    {
        return header;
    }

    //----------------//
    // getHeaderStart //
    //----------------//
    /**
     * @return the start of header area
     */
    public int getHeaderStart ()
    {
        return header.start;
    }

    //---------------//
    // getHeaderStop //
    //---------------//
    /**
     * Report the abscissa at end of staff StaffHeader area.
     * The StaffHeader is the zone at the beginning of the staff, dedicated to clef, plus key-sig
     * if any, plus time-sig if any. The StaffHeader cannot contain notes, stems, beams, etc.
     *
     * @return StaffHeader end abscissa
     */
    public int getHeaderStop ()
    {
        return header.stop;
    }

    //-----------//
    // getHeight //
    //-----------//
    /**
     * Report the mean height of the staff, between first and last line.
     *
     * @return the mean staff height
     */
    public int getHeight ()
    {
        return getSpecificScale().getInterline() * (lines.size() - 1);
    }

    //-------//
    // getId //
    //-------//
    /**
     * Report the staff id, counted from 1 in the sheet, regardless of containing system
     * and part.
     *
     * @return the staff id
     */
    public int getId ()
    {
        return id;
    }

    //----------------//
    // getIndexInPart //
    //----------------//
    /**
     * Report the index of this staff in the containing part.
     *
     * @return the index in containing part
     */
    public int getIndexInPart ()
    {
        Part part = getPart();
        List<Staff> staves = part.getStaves();

        return staves.indexOf(this);
    }

    /**
     * @return the keyStop
     */
    public Integer getKeyStop ()
    {
        if (header.keyRange.valid) {
            return header.keyRange.stop;
        }

        return null;
    }

    //-------------//
    // getLastLine //
    //-------------//
    /**
     * Report the last line in the series.
     *
     * @return the last line
     */
    public LineInfo getLastLine ()
    {
        return lines.get(lines.size() - 1);
    }

    //--------------//
    // getLedgerMap //
    //--------------//
    public SortedMap<Integer, List<LedgerInter>> getLedgerMap ()
    {
        return ledgerMap;
    }

    //------------//
    // getLedgers //
    //------------//
    /**
     * Report the ordered set of ledgers, if any, for a given index.
     *
     * @param lineIndex the precise line index that specifies algebraic
     *                  distance from staff
     * @return the proper abscissa-ordered set of ledgers, or null
     */
    public List<LedgerInter> getLedgers (int lineIndex)
    {
        return ledgerMap.get(lineIndex);
    }

    //----------//
    // getLeftY //
    //----------//
    /**
     * Report the ordinate at left side of staff for the desired vertical line
     *
     * @param verticalSide TOP or BOTTOM
     * @return the top of bottom ordinate on left side of staff
     */
    public int getLeftY (VerticalSide verticalSide)
    {
        return getLine(verticalSide).yAt(left);
    }

    //---------//
    // getLine //
    //---------//
    /**
     * Report first or last staff line, according to desired vertical
     * side.
     *
     * @param side TOP for first, BOTTOM for last
     * @return the staff line
     */
    public LineInfo getLine (VerticalSide side)
    {
        if (side == TOP) {
            return lines.get(0);
        } else {
            return lines.get(lines.size() - 1);
        }
    }

    //--------------//
    // getLineCount //
    //--------------//
    /**
     * Report the number of lines in this staff.
     *
     * @return the number of lines (6, 4, ...)
     */
    public int getLineCount ()
    {
        return lines.size();
    }

    //----------//
    // getLines //
    //----------//
    /**
     * Report the sequence of lines.
     *
     * @return the list of lines in this staff
     */
    public List<LineInfo> getLines ()
    {
        return lines;
    }

    //-------------//
    // getLinesEnd //
    //-------------//
    /**
     * Report the ending abscissa of the staff lines.
     *
     * @param side desired horizontal side
     * @return the abscissa corresponding to lines extrema
     */
    public double getLinesEnd (HorizontalSide side)
    {
        if (side == HorizontalSide.LEFT) {
            double linesLeft = Integer.MAX_VALUE;

            for (LineInfo line : lines) {
                linesLeft = Math.min(linesLeft, line.getEndPoint(LEFT).getX());
            }

            return linesLeft;
        } else {
            double linesRight = Integer.MIN_VALUE;

            for (LineInfo line : lines) {
                linesRight = Math.max(linesRight, line.getEndPoint(RIGHT).getX());
            }

            return linesRight;
        }
    }

    //------------------//
    // getMeanInterline //
    //------------------//
    /**
     * Return the actual mean interline as observed on this staff.
     *
     * @return the precise mean interline value
     */
    public double getMeanInterline ()
    {
        Population dys = new Population();

        int dx = specificScale.getInterline();
        int xMin = getAbscissa(LEFT);
        int xMax = getAbscissa(RIGHT);

        for (double x = xMin; x <= xMax; x += dx) {
            double prevY = -1;

            for (LineInfo line : lines) {
                double y = line.yAt(x);

                if (prevY != -1) {
                    double dy = y - prevY;
                    dys.includeValue(dy);
                }

                prevY = y;
            }
        }

        double mean = dys.getMeanValue();

        //        logger.info(
        //            String.format("Staff#%d dy:%.2f std:%.2f", id, mean, dys.getStandardDeviation()));
        //
        return mean;
    }

    //-----------------//
    // getNotePosition //
    //-----------------//
    /**
     * Report the precise position for a note-like entity with respect
     * to this staff, taking ledgers (if any) into account.
     *
     * @param point the absolute location of the provided note
     * @return the detailed note position
     */
    public NotePosition getNotePosition (Point2D point)
    {
        double pitch = pitchPositionOf(point);
        IndexedLedger bestLedger = null;

        // If we are rather far from the staff, try getting help from ledgers
        if (Math.abs(pitch) > lines.size()) {
            bestLedger = getClosestLedger(point);

            if (bestLedger != null) {
                Point2D center = bestLedger.ledger.getGlyph().getCenter();
                int ledgerPitch = getLedgerPitchPosition(bestLedger.index);
                double deltaPitch = (2d * (point.getY() - center.getY())) / specificScale.getInterline();
                pitch = ledgerPitch + deltaPitch;
            }
        }

        return new NotePosition(this, pitch, bestLedger);
    }

    //---------//
    // getPart //
    //---------//
    /**
     * Report the part that contains this staff.
     *
     * @return the containing part
     */
    public Part getPart ()
    {
        return system.getPartOf(this);
    }

    //----------//
    // getPeaks //
    //----------//
    /**
     * @return the peaks
     */
    public List<StaffPeak> getPeaks ()
    {
        return Collections.unmodifiableList(peaks);
    }

    //------------//
    // getSideBar //
    //------------//
    public BarlineInter getSideBar (HorizontalSide side)
    {
        return sideBars.get(side);
    }

    //------------------//
    // getSpecificScale //
    //------------------//
    /**
     * Report the <b>specific</b> staff scale, which may have a
     * different interline value than the page average.
     *
     * @return the staff scale
     */
    public Scale getSpecificScale ()
    {
        if (specificScale != null) {
            // Return the specific scale of this staff
            return specificScale;
        } else {
            // Return the scale of the sheet
            //logger.warn("No specific scale available");
            return system.getSheet().getScale();
        }
    }

    //-----------//
    // getSystem //
    //-----------//
    /**
     * @return the system
     */
    public SystemInfo getSystem ()
    {
        return system;
    }

    /**
     * @return the timeStop
     */
    public Integer getTimeStop ()
    {
        if (header.timeRange.valid) {
            return header.timeRange.stop;
        }

        return null;
    }

    //-----------------//
    // insertBracePeak //
    //-----------------//
    public void insertBracePeak (StaffPeak.Brace bracePeak)
    {
        peaks.add(0, bracePeak);
    }

    //---------//
    // isDummy //
    //---------//
    public boolean isDummy ()
    {
        return dummy;
    }

    //---------//
    // isShort //
    //---------//
    /**
     * Report whether the staff is a short (partial) one, which means
     * that there is another staff on left or right side.
     *
     * @return the isShort
     */
    public boolean isShort ()
    {
        return (isShort != null) && (isShort == true);
    }

    //-----------------//
    // pitchPositionOf //
    //-----------------//
    /**
     * Compute an approximation of the pitch position of a pixel point, since it is
     * based only on distance to staff, with no consideration for ledgers.
     *
     * @param pt the pixel point
     * @return the pitch position
     */
    public double pitchPositionOf (Point2D pt)
    {
        double top = getFirstLine().yAt(pt.getX());
        double bottom = getLastLine().yAt(pt.getX());

        return ((lines.size() - 1) * ((2 * pt.getY()) - bottom - top)) / (bottom - top);
    }

    //-----------------//
    // pitchToOrdinate //
    //-----------------//
    public double pitchToOrdinate (double x,
                                   double pitch)
    {
        double top = getFirstLine().yAt(x);
        double bottom = getLastLine().yAt(x);

        return 0.5 * (top + bottom + ((pitch * (bottom - top)) / (lines.size() - 1)));
    }

    //-------------------//
    // removeAttachments //
    //-------------------//
    @Override
    public int removeAttachments (String prefix)
    {
        return attachments.removeAttachments(prefix);
    }

    //-----------//
    // removeBar //
    //-----------//
    /**
     * Remove the provided instance of Barline from internal staff collection.
     *
     * @param bar the provided bar to remove
     * @return true if actually removed
     */
    public boolean removeBar (BarlineInter bar)
    {
        // Purge sideBars if needed
        for (Iterator<Entry<HorizontalSide, BarlineInter>> it = sideBars.entrySet().iterator();
                it.hasNext();) {
            Entry<HorizontalSide, BarlineInter> entry = it.next();

            if (entry.getValue() == bar) {
                it.remove();
            }
        }

        // Purge bars
        return bars.remove(bar);
    }

    //--------------//
    // removeLedger //
    //--------------//
    /**
     * Remove a ledger from staff collection.
     *
     * @param ledger the ledger to remove
     * @return true if actually removed, false if not found
     */
    public boolean removeLedger (LedgerInter ledger)
    {
        assert ledger != null : "Cannot remove a null ledger";
        logger.debug("removing {}", ledger);

        // Browse all staff ledger indices
        for (Entry<Integer, List<LedgerInter>> entry : ledgerMap.entrySet()) {
            List<LedgerInter> ledgerSet = entry.getValue();

            if (ledgerSet.remove(ledger)) {
                if (ledgerSet.isEmpty()) {
                    // No ledger is left on this line index, thus remove the map entry
                    ledgerMap.remove(entry.getKey());
                }

                return true;
            }
        }

        // Not found
        logger.debug("Could not find ledger {}", ledger);

        return false;
    }

    //------------//
    // removeNote //
    //------------//
    /**
     * Remove a note (head or rest) from staff collection.
     *
     * @param note the note to remove
     * @return true if actually removed, false if not found
     */
    public boolean removeNote (AbstractNoteInter note)
    {
        return notes.remove(note);
    }

    //------------------//
    // removeOtherInter //
    //------------------//
    public void removeOtherInter (AbstractInter inter)
    {
        others.remove(inter);
    }

    //-------------//
    // removePeaks //
    //-------------//
    public void removePeaks (Collection<? extends StaffPeak> toRemove)
    {
        peaks.removeAll(toRemove);
    }

    //--------//
    // render //
    //--------//
    /**
     * Paint each staff line, perhaps with its defining points.
     *
     * @param g the graphics context
     * @return true if something has been actually drawn
     */
    public boolean render (Graphics2D g)
    {
        //        if (area != null) {
        //            LineInfo firstLine = getFirstLine();
        //            LineInfo lastLine = getLastLine();
        //
        //            if ((firstLine != null) && (lastLine != null)) {
        //                Rectangle clip = g.getClipBounds();
        //
        //                if ((clip != null) && !clip.intersects(getAreaBounds())) {
        //                    return false;
        //                }
        //            }
        //        }
        //
        final boolean showPoints = constants.showDefiningPoints.isSet();
        final Scale scale = system.getSheet().getScale();
        final double pointWidth = scale.toPixelsDouble(constants.definingPointSize);

        // Draw each staff line
        for (LineInfo line : lines) {
            line.renderLine(g, showPoints, pointWidth);
        }

        return true;
    }

    //-------------------//
    // renderAttachments //
    //-------------------//
    @Override
    public void renderAttachments (Graphics2D g)
    {
        attachments.renderAttachments(g);
    }

    //-----------//
    // replicate //
    //-----------//
    public Staff replicate ()
    {
        Staff replicate = new Staff(0, left, right, null, null);

        return replicate;
    }

    //-------------//
    // setAbscissa //
    //-------------//
    /**
     * Set the staff abscissa of the provided side.
     *
     * @param side provided side
     * @param val  abscissa of staff end
     */
    public void setAbscissa (HorizontalSide side,
                             int val)
    {
        if (side == HorizontalSide.LEFT) {
            left = val;
        } else {
            right = val;
        }
    }

    //---------//
    // setArea //
    //---------//
    public void setArea (Area area)
    {
        this.area = area;
    }

    //-------------//
    // setBarPeaks //
    //-------------//
    /**
     * @param peaks the peaks to set
     */
    public void setBarPeaks (List<StaffPeak> peaks)
    {
        this.peaks = peaks;
    }

    //---------//
    // setBars //
    //---------//
    /**
     * @param bars the bars to set
     */
    public void setBars (List<BarlineInter> bars)
    {
        this.bars = bars;
        retrieveSideBars();
    }

    /**
     * @param clefStop the clefStop to set
     */
    public void setClefStop (int clefStop)
    {
        header.clefRange.stop = clefStop;
        header.clefRange.valid = true;
    }

    //----------//
    // setDummy //
    //----------//
    public void setDummy ()
    {
        dummy = true;
    }

    //-----------//
    // setHeader //
    //-----------//
    /**
     * @param header the StaffHeader information
     */
    public void setHeader (StaffHeader header)
    {
        this.header = header;
    }

    //---------------//
    // setHeaderStop //
    //---------------//
    /**
     * Refine the abscissa of StaffHeader break.
     *
     * @param headerStop the refined StaffHeader end value
     */
    public void setHeaderStop (int headerStop)
    {
        header.stop = headerStop;
    }

    /**
     * @param keyStop the keyStop to set
     */
    public void setKeyStop (Integer keyStop)
    {
        header.keyRange.stop = keyStop;
        header.keyRange.valid = true;
    }

    //----------//
    // setShort //
    //----------//
    /**
     * Flag this staff as a "short" one, because it is displayed side
     * by side with another one.
     * This indicates these two staves belong to separate systems, displayed
     * side by side, rather than one under the other.
     */
    public void setShort ()
    {
        isShort = true;
    }

    //-----------//
    // setSystem //
    //-----------//
    /**
     * @param system the system to set
     */
    public void setSystem (SystemInfo system)
    {
        this.system = system;
    }

    /**
     * @param timeStop the timeStop to set
     */
    public void setTimeStop (Integer timeStop)
    {
        header.timeRange.stop = timeStop;
        header.timeRange.valid = true;
    }

    //---------------//
    // simplifyLines //
    //---------------//
    /**
     * Replace the transient StaffFilament instances by persistent StaffLine instances.
     *
     * @param sheet the sheet to process
     * @return the original StaffFilaments
     */
    public List<LineInfo> simplifyLines (Sheet sheet)
    {
        if (getFirstLine() instanceof StaffLine) {
            logger.error("Staff lines have already been simplified!");

            return null;
        }

        final GlyphIndex glyphIndex = sheet.getGlyphIndex();
        List<LineInfo> copies = new ArrayList<LineInfo>(lines);
        lines.clear();

        for (LineInfo line : copies) {
            StaffFilament staffFilament = (StaffFilament) line;
            StaffLine staffLine = staffFilament.toStaffLine(glyphIndex);
            lines.add(staffLine);
        }

        return copies;
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder("{Staff");

        sb.append(" id=").append(getId());

        if (isShort()) {
            sb.append(" SHORT");
        }

        sb.append(" left:").append(left);
        sb.append(" right:").append(right);

        sb.append("}");

        return sb.toString();
    }

    //-----------//
    // xOverlaps //
    //-----------//
    /**
     * Report whether staff horizontally overlaps that other staff.
     *
     * @param that the other staff
     * @return true if overlap
     */
    public boolean xOverlaps (Staff that)
    {
        final double commonLeft = Math.max(left, that.left);
        final double commonRight = Math.min(right, that.right);

        return commonRight > commonLeft;
    }

    //-----------//
    // yOverlaps //
    //-----------//
    /**
     * Report whether staff vertically overlaps that other staff.
     *
     * @param that the other staff
     * @return true if overlap
     */
    public boolean yOverlaps (Staff that)
    {
        final double thisTop = this.getFirstLine().getEndPoint(LEFT).getY();
        final double thatTop = that.getFirstLine().getEndPoint(LEFT).getY();
        final double commonTop = Math.max(thisTop, thatTop);

        final double thisBottom = this.getLastLine().getEndPoint(LEFT).getY();
        final double thatBottom = that.getLastLine().getEndPoint(LEFT).getY();
        final double commonBottom = Math.min(thisBottom, thatBottom);

        return commonBottom > commonTop;
    }

    //----------------//
    // afterUnmarshal //
    //----------------//
    /**
     * Called after all the properties (except IDREF) are unmarshalled for this object,
     * but before this object is set to the parent object.
     * We use this call-back method to re-assign staff to contained inters.
     */
    @SuppressWarnings("unused")
    private void afterUnmarshal (Unmarshaller um,
                                 Object parent)
    {
        if (header != null) {
            if (header.clef != null) {
                header.clef.setStaff(this);
            }

            if (header.key != null) {
                header.key.setStaff(this);

                for (Inter alter : header.key.getMembers()) {
                    alter.setStaff(this);
                }
            }

            if (header.time != null) {
                header.time.setStaff(this);

                if (header.time instanceof InterEnsemble) {
                    for (Inter member : ((InterEnsemble) header.time).getMembers()) {
                        member.setStaff(this);
                    }
                }
            }
        }

        for (BarlineInter bar : bars) {
            bar.setStaff(this);
        }

        retrieveSideBars();

        //
        //        // Oops: this won't work because notes are IDREF's !!!
        //        for (AbstractNoteInter note : notes) {
        //            note.setStaff(this);
        //        }
        //
        for (List<LedgerInter> ledgerSet : ledgerMap.values()) {
            for (LedgerInter ledger : ledgerSet) {
                ledger.setStaff(this);
            }
        }
    }

    //------------------//
    // retrieveSideBars //
    //------------------//
    /**
     * Remember bars on left and right sides, if any.
     */
    private void retrieveSideBars ()
    {
        if (!bars.isEmpty()) {
            for (HorizontalSide side : HorizontalSide.values()) {
                final int end = getAbscissa(side);
                final BarlineInter bar = bars.get((side == LEFT) ? 0 : (bars.size() - 1));
                final Rectangle barBox = bar.getBounds();

                if ((barBox.x <= end) && (end <= ((barBox.x + barBox.width) - 1))) {
                    sideBars.put(side, bar);
                }
            }
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //---------//
    // Adapter //
    //---------//
    public static class Adapter
            extends XmlAdapter<Integer, Staff>
    {
        //~ Methods --------------------------------------------------------------------------------

        @Override
        public Integer marshal (Staff staff)
                throws Exception
        {
            return staff.getId();
        }

        @Override
        public Staff unmarshal (Integer id)
                throws Exception
        {
            return null; // Handled later
        }
    }

    //---------------//
    // IndexedLedger //
    //---------------//
    /**
     * This combines the ledger with the index relative to the
     * hosting staff.
     */
    public static class IndexedLedger
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** The ledger. */
        public final LedgerInter ledger;

        /** Staff-based line index. (-1, -2, ... above, +1, +2, ... below) */
        public final int index;

        //~ Constructors ---------------------------------------------------------------------------
        public IndexedLedger (LedgerInter ledger,
                              int index)
        {
            this.ledger = ledger;
            this.index = index;
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Boolean showDefiningPoints = new Constant.Boolean(
                false,
                "Should we show defining points?");

        private final Scale.Fraction definingPointSize = new Scale.Fraction(
                0.05,
                "Display width of a defining point");
    }

    //---------------//
    // LedgerAdapter //
    //---------------//
    private static class LedgerAdapter
            extends XmlAdapter<LedgersValue, TreeMap<Integer, List<LedgerInter>>>
    {
        //~ Methods --------------------------------------------------------------------------------

        @Override
        public LedgersValue marshal (TreeMap<Integer, List<LedgerInter>> map)
                throws Exception
        {
            if (map.isEmpty()) {
                return null;
            }

            LedgersValue value = new LedgersValue();

            for (Entry<Integer, List<LedgerInter>> entry : map.entrySet()) {
                value.entries.add(
                        new LedgersEntry(entry.getKey(), new ArrayList<LedgerInter>(entry.getValue())));
            }

            return value;
        }

        @Override
        public TreeMap<Integer, List<LedgerInter>> unmarshal (LedgersValue value)
                throws Exception
        {
            if (value == null) {
                return null;
            }

            TreeMap<Integer, List<LedgerInter>> map = new TreeMap<Integer, List<LedgerInter>>();

            for (LedgersEntry entry : value.entries) {
                try {
                    List<LedgerInter> ledgerSet = new ArrayList<LedgerInter>();

                    // Safer
                    if (entry.ledgers != null) {
                        ledgerSet.addAll(entry.ledgers);
                        map.put(entry.index, ledgerSet);
                    }
                } catch (Throwable ex) {
                    logger.error("Error unmarshalling " + entry.ledgers, ex);
                }
            }

            return map;
        }
    }

    //--------------//
    // LedgersEntry //
    //--------------//
    @XmlRootElement(name = "ledgers-line")
    private static class LedgersEntry
    {
        //~ Instance fields ------------------------------------------------------------------------

        @XmlAttribute
        private final int index;

        @XmlElement(name = "ledger")
        private final ArrayList<LedgerInter> ledgers;

        //~ Constructors ---------------------------------------------------------------------------
        public LedgersEntry ()
        {
            this.index = 0;
            this.ledgers = null;
        }

        public LedgersEntry (int index,
                             ArrayList<LedgerInter> ledgers)
        {
            this.index = index;
            this.ledgers = ledgers;
        }
    }

    //--------------//
    // LedgersValue //
    //--------------//
    private static class LedgersValue
    {
        //~ Instance fields ------------------------------------------------------------------------

        @XmlElement(name = "ledgers-entry")
        ArrayList<LedgersEntry> entries = new ArrayList<LedgersEntry>();

        //~ Constructors ---------------------------------------------------------------------------
        public LedgersValue ()
        {
        }
    }
}