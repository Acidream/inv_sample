package com.example.currentaccount.application.port;

import com.example.currentaccount.domain.Booking;
import java.util.List;
import java.util.UUID;

public interface Bookings {

    void add(Booking booking);

    /** Newest first. */
    List<Booking> latest(UUID accountId, int limit);
}
