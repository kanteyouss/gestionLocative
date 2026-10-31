package com.gestionLocative.gestionLocative.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BailRequestDto extends AbstractClean implements RequestDto {
    @NotNull(message = "La date de début est obligatoire")
    private Date dateDebut;
    private Date dateFin;
    @NotNull(message = "Le montant du loyer est obligatoire")
    @Positive(message = "Le loyer doit être positif")
    private BigDecimal montantLoyer;
    private BigDecimal provisionCharges;
    private BigDecimal depotGarantie;
    @NotNull(message = "L'appartement est obligatoire")
    private Long appartementId;
    @NotNull(message = "Le locataire est obligatoire")
    private Long locataireId;
}