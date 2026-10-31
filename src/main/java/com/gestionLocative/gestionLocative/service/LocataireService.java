package com.gestionLocative.gestionLocative.service;

import com.gestionLocative.gestionLocative.dto.AbstractClean;
import com.gestionLocative.gestionLocative.dto.*;
import com.gestionLocative.gestionLocative.entity.Bail;
import com.gestionLocative.gestionLocative.entity.Locataire;
import com.gestionLocative.gestionLocative.exception.DoublonRessourceException;
import com.gestionLocative.gestionLocative.exception.NotFoundException;
import com.gestionLocative.gestionLocative.exception.RessourceUtiliseeException;
import com.gestionLocative.gestionLocative.repository.BailRepository;
import com.gestionLocative.gestionLocative.repository.LocataireRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LocataireService extends AbstractCrudService {

    private final LocataireRepository locataireRepository;
    private final BailRepository bailRepository;

    public LocataireService(LocataireRepository locataireRepository,
                            BailRepository bailRepository) {
        super(locataireRepository);
        this.locataireRepository = locataireRepository;
        this.bailRepository = bailRepository;
    }

    public Locataire toEntity(LocataireRequestDto dto) {
        return Locataire.builder()
                .nom(dto.getNom())
                .prenom(dto.getPrenom())
                .numero(dto.getNumero())
                .email(dto.getEmail())
                .build();
    }

    @Override
    public ResponseDto toDto(Object entity) {
        Locataire l = (Locataire) entity;
        return LocataireResponseDto.builder()
                .id(l.getId())
                .nom(l.getNom())
                .prenom(l.getPrenom())
                .numero(l.getNumero())
                .email(l.getEmail())
                .build();
    }

    public Page<ResponseDto> getAll(int page, int taille) {
        return getAll(page, taille, Sort.by("nom"));
    }

    @Override
    public ResponseDto create(RequestDto request) {
        LocataireRequestDto dto = (LocataireRequestDto) request;
        String email = AbstractClean.cleanText(dto.getEmail());
        if (email != null && !email.isBlank()
                && locataireRepository.existsByEmail(email)) {
            throw new DoublonRessourceException("Un locataire avec l'email '" + email + "' existe déjà.");
        }
        dto.setNom(AbstractClean.cleanText(dto.getNom()));
        dto.setPrenom(AbstractClean.cleanText(dto.getPrenom()));
        dto.setNumero(AbstractClean.cleanText(dto.getNumero()));
        dto.setEmail(email);
        return toDto(locataireRepository.save(toEntity(dto)));
    }

    @Override
    public ResponseDto update(Long id, RequestDto request) {
        LocataireRequestDto dto = (LocataireRequestDto) request;
        Locataire locataire = locataireRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Locataire non trouvé"));
        String email = AbstractClean.cleanText(dto.getEmail());
        if (email != null && !email.isBlank()
                && locataireRepository.existsByEmailAndIdNot(email, id)) {
            throw new DoublonRessourceException("Un autre locataire avec l'email '" + email + "' existe déjà.");
        }
        dto.setNom(AbstractClean.cleanText(dto.getNom()));
        dto.setPrenom(AbstractClean.cleanText(dto.getPrenom()));
        dto.setNumero(AbstractClean.cleanText(dto.getNumero()));
        dto.setEmail(email);
        locataire.setNom(dto.getNom());
        locataire.setPrenom(dto.getPrenom());
        locataire.setNumero(dto.getNumero());
        locataire.setEmail(dto.getEmail());
        return toDto(locataireRepository.save(locataire));
    }

    public ResponseDto delete(Long id) {
        Locataire locataire = locataireRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        for (Bail bail : bailRepository.findByLocataireId(id)) {
            if (bailEnCours(bail)) {
                throw new RessourceUtiliseeException("Locataire", id, "Bail");
            }
        }
        locataireRepository.deleteById(id);
        return toDto(locataire);
    }

    private boolean bailEnCours(Bail bail) {
        Date aujourdHui = new Date();
        Date debut = bail.getDateDebut();
        Date fin = bail.getDateFin();
        boolean commence = debut != null && !debut.after(aujourdHui);
        boolean nonTermine = fin == null || !fin.before(aujourdHui);
        return commence && nonTermine;
    }
}