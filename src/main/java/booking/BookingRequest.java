package booking;

import lombok.Data;

@Data
public class BookingRequest {
    private String service;
    private String date;
    private Integer nights;
    private String timeSlot;
    private Integer guests;
    private Integer children;
    private Double total;
    private String name;
    private String contact;
    private String comment;
}
