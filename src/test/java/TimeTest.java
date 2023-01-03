import com.pkfare.supplier.logics.Times;

public class TimeTest {

    public static void main(String[] args) {
        System.out.println(Times.of("04-29-202320:10","MM-dd-yyyyHH:mm").to("yyyy-MM-dd HH:mm"));
    }

}
