package pl.tajchert.fitsurvivor;

/**
 * Created by Michal Tajchert on 2015-05-31.
 */
public class ConsecutiveDays {
    public long stepsTotal;
    public long stepsPerDay;
    public int days;

    public ConsecutiveDays(long stepsTotal, int days) {
        this.stepsTotal = stepsTotal;
        this.days = days;
        if(days != 0) {
            this.stepsPerDay = stepsTotal / days;
        }
    }
}
