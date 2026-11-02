package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.City;
import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.graph.GraphData.Road;

import java.util.List;

/** Geometry uses X/Z city footprints and true 3D road distances. */
public final class Geometry {
    private static final double EPS = 1e-9;
    private Geometry() {}

    public static double distanceSquared(Point a, Point b) {
        if (!a.dimension().equals(b.dimension())) return Double.POSITIVE_INFINITY;
        return square(a.x()-b.x()) + square(a.y()-b.y()) + square(a.z()-b.z());
    }

    public static Point closestOnSegment(Point point, Point a, Point b) {
        requireSameDimension(point, a);
        requireSameDimension(a, b);
        double dx=b.x()-a.x(), dy=b.y()-a.y(), dz=b.z()-a.z();
        double length=dx*dx+dy*dy+dz*dz;
        double t=length <= EPS*EPS ? 0 : clamp(((point.x()-a.x())*dx+(point.y()-a.y())*dy+(point.z()-a.z())*dz)/length);
        return new Point(a.dimension(), a.x()+t*dx, a.y()+t*dy, a.z()+t*dz);
    }

    public static Point closestOnRoad(Point point, Road road) {
        if (!point.dimension().equals(road.dimension()))
            throw new IllegalArgumentException("Cannot project between dimensions");
        Point best = road.points().get(0);
        double bestDistance = distanceSquared(point, best);
        for (int i=1; i<road.points().size(); i++) {
            Point candidate=closestOnSegment(point, road.points().get(i-1), road.points().get(i));
            double distance=distanceSquared(point,candidate);
            if (distance < bestDistance) { best=candidate; bestDistance=distance; }
        }
        return best;
    }

    public static double distanceToRoadSquared(Point point, Road road) {
        if (!point.dimension().equals(road.dimension())) return Double.POSITIVE_INFINITY;
        return distanceSquared(point, closestOnRoad(point, road));
    }

    public static boolean contains(City city, Point point) {
        if (!city.dimension().equals(point.dimension())) return false;
        return containsXZ(city.vertices(),point.x(),point.z());
    }

    /** Polygon boundaries count as inside; the closing edge is implicit. */
    public static boolean containsXZ(List<Point> polygon, double x, double z) {
        boolean inside=false;
        for (int i=0,j=polygon.size()-1; i<polygon.size(); j=i++) {
            Point a=polygon.get(j),b=polygon.get(i);
            if (onSegmentXZ(a.x(),a.z(),b.x(),b.z(),x,z)) return true;
            if ((a.z()>z)!=(b.z()>z) && x < (b.x()-a.x())*(z-a.z())/(b.z()-a.z())+a.x()) inside=!inside;
        }
        return inside;
    }

    /** Cities have no altitude limit: projecting to their footprint preserves requested Y. */
    public static Point closestInCity(Point point, City city) {
        if (!point.dimension().equals(city.dimension()))
            throw new IllegalArgumentException("Cannot project between dimensions");
        if (contains(city,point)) return point;
        Point best=null;
        double bestDistance=Double.POSITIVE_INFINITY;
        for (int i=0,j=city.vertices().size()-1; i<city.vertices().size(); j=i++) {
            Point a=city.vertices().get(j),b=city.vertices().get(i);
            double dx=b.x()-a.x(),dz=b.z()-a.z();
            double length=dx*dx+dz*dz;
            double t=length<=EPS*EPS ? 0 : clamp(((point.x()-a.x())*dx+(point.z()-a.z())*dz)/length);
            Point candidate=new Point(point.dimension(),a.x()+t*dx,point.y(),a.z()+t*dz);
            double distance=distanceSquared(point,candidate);
            if (distance < bestDistance) { best=candidate; bestDistance=distance; }
        }
        return best;
    }

    public static boolean roadIntersectsCity(Road road, City city) {
        if (!road.dimension().equals(city.dimension())) return false;
        for (int i=1; i<road.points().size(); i++) {
            Point a=road.points().get(i-1),b=road.points().get(i);
            if (contains(city,a) || contains(city,b)) return true;
            for (int k=0,j=city.vertices().size()-1; k<city.vertices().size(); j=k++)
                if (segmentsIntersectXZ(a,b,city.vertices().get(j),city.vertices().get(k))) return true;
        }
        return false;
    }

    /** Squared minimum distance between two finite line segments, including degenerate segments. */
    public static double segmentDistanceSquared(Point a, Point b, Point c, Point d) {
        if (!a.dimension().equals(c.dimension())) return Double.POSITIVE_INFINITY;
        requireSameDimension(a,b);
        requireSameDimension(c,d);
        double ux=b.x()-a.x(),uy=b.y()-a.y(),uz=b.z()-a.z();
        double vx=d.x()-c.x(),vy=d.y()-c.y(),vz=d.z()-c.z();
        double wx=a.x()-c.x(),wy=a.y()-c.y(),wz=a.z()-c.z();
        double aa=ux*ux+uy*uy+uz*uz, ee=vx*vx+vy*vy+vz*vz;
        double ff=vx*wx+vy*wy+vz*wz;
        double s,t;
        if (aa<=EPS*EPS && ee<=EPS*EPS) return distanceSquared(a,c);
        if (aa<=EPS*EPS) { s=0; t=clamp(ff/ee); }
        else {
            double cc=ux*wx+uy*wy+uz*wz;
            if (ee<=EPS*EPS) { t=0; s=clamp(-cc/aa); }
            else {
                double bb=ux*vx+uy*vy+uz*vz;
                double denom=aa*ee-bb*bb;
                s=denom>EPS*EPS*aa*ee ? clamp((bb*ff-cc*ee)/denom) : 0;
                t=(bb*s+ff)/ee;
                if (t<0) { t=0; s=clamp(-cc/aa); }
                else if (t>1) { t=1; s=clamp((bb-cc)/aa); }
            }
        }
        return square(wx+s*ux-t*vx)+square(wy+s*uy-t*vy)+square(wz+s*uz-t*vz);
    }

    public static boolean segmentsIntersectXZ(Point a, Point b, Point c, Point d) {
        double o1=orientation(a,b,c),o2=orientation(a,b,d),o3=orientation(c,d,a),o4=orientation(c,d,b);
        if (((o1>EPS && o2 < -EPS)||(o1 < -EPS && o2>EPS)) &&
                ((o3>EPS && o4 < -EPS)||(o3 < -EPS && o4>EPS))) return true;
        return Math.abs(o1)<=EPS && onSegmentXZ(a.x(),a.z(),b.x(),b.z(),c.x(),c.z())
                || Math.abs(o2)<=EPS && onSegmentXZ(a.x(),a.z(),b.x(),b.z(),d.x(),d.z())
                || Math.abs(o3)<=EPS && onSegmentXZ(c.x(),c.z(),d.x(),d.z(),a.x(),a.z())
                || Math.abs(o4)<=EPS && onSegmentXZ(c.x(),c.z(),d.x(),d.z(),b.x(),b.z());
    }

    public static boolean isSimplePolygon(List<Point> polygon) {
        double area=0;
        for (int i=0;i<polygon.size();i++) {
            Point a=polygon.get(i),b=polygon.get((i+1)%polygon.size());
            if (Math.abs(a.x()-b.x())<=EPS && Math.abs(a.z()-b.z())<=EPS) return false;
            // Translate before area calculation to retain precision near world boundaries.
            Point origin=polygon.get(0);
            area+=(a.x()-origin.x())*(b.z()-origin.z())-(b.x()-origin.x())*(a.z()-origin.z());
            for (int j=i+1;j<polygon.size();j++) {
                if (j==i+1 || (i==0 && j==polygon.size()-1)) continue;
                if (segmentsIntersectXZ(a,b,polygon.get(j),polygon.get((j+1)%polygon.size()))) return false;
            }
        }
        return Math.abs(area)>EPS;
    }

    private static boolean onSegmentXZ(double ax,double az,double bx,double bz,double px,double pz) {
        double cross=(bx-ax)*(pz-az)-(bz-az)*(px-ax);
        return Math.abs(cross)<=EPS*Math.max(1,Math.hypot(bx-ax,bz-az)) &&
                px>=Math.min(ax,bx)-EPS && px<=Math.max(ax,bx)+EPS && pz>=Math.min(az,bz)-EPS && pz<=Math.max(az,bz)+EPS;
    }
    private static double orientation(Point a,Point b,Point c) { return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x()); }
    private static double clamp(double t) { return Math.max(0,Math.min(1,t)); }
    private static double square(double x) { return x*x; }
    private static void requireSameDimension(Point a,Point b) {
        if (!a.dimension().equals(b.dimension())) throw new IllegalArgumentException("Geometry dimensions differ");
    }
}
