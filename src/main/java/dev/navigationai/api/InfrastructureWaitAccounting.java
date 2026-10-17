package dev.navigationai.api;

/** Cumulative child-task wait, retaining the last contribution when a route is replaced. */
public final class InfrastructureWaitAccounting {
    private NavigationTask navigation;
    private ExcavationTask excavation;
    private long navigationSeen,excavationSeen,total;
    public long observe(NavigationTask nextNavigation,ExcavationTask nextExcavation) {
        if(navigation!=null){long value=navigation.infrastructureWaitTicks();total+=Math.max(0,value-navigationSeen);navigationSeen=value;}
        if(excavation!=null){long value=excavation.infrastructureWaitTicks();total+=Math.max(0,value-excavationSeen);excavationSeen=value;}
        if(navigation!=nextNavigation){navigation=nextNavigation;navigationSeen=0;if(navigation!=null){navigationSeen=navigation.infrastructureWaitTicks();total+=navigationSeen;}}
        if(excavation!=nextExcavation){excavation=nextExcavation;excavationSeen=0;if(excavation!=null){excavationSeen=excavation.infrastructureWaitTicks();total+=excavationSeen;}}
        return total;
    }
    public void reset(){navigation=null;excavation=null;navigationSeen=excavationSeen=total=0;}
}
