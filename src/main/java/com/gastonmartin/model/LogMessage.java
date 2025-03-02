package com.gastonmartin.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class LogMessage {
    String message;
    String source;
    Long size;

}
